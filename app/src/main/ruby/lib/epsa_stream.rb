# Streaming reader for v4 .epsa archives.
#
# Implements the duck-typed IO surface that PhysFS.mount_io expects: read,
# seek, tell, length, close, duplicate, flush. Decrypts on demand chunk by
# chunk so the plaintext archive never lands on disk.
#
# Threading: PhysFS may call duplicate() and use the clones from different
# threads concurrently. Each duplicate has its own file handle, current
# offset AND its own verified-chunk cache — duplicates share nothing
# mutable. Earlier the verified-chunk Set was shared across duplicates
# (gated by a Mutex) to avoid duplicate MAC work; that turned out to
# deadlock under concurrent compile + render Ruby Threads, and the cache
# savings were negligible because PhysFS duplicates are short-lived
# (one PHYSFS_openRead -> one duplicate -> destroyed at file close).

require 'openssl'
require 'set'

# NOTE: no `require_relative 'epsa_format'` here. This file ships as a
# prelude concatenated into the compile.rb / copy_saves.rb script content
# and executed via the embedded Ruby VM's `ruby_script_create_from_content`
# path — which has no __FILE__, so `require_relative` would raise
# `LoadError: cannot infer basepath` and abort the script.
# CompilationEngine.ARCHIVE_PRELUDES guarantees epsa_format.rb's content
# runs before this file's content, so EpsaFormat is already defined.
# Desktop unit tests explicitly load epsa_format before this file.

# Guard the whole class body — same rationale as epsa_format.rb: this file is
# re-evaluated on every compile-VM script (check_engine / compile / copy_saves),
# and Ruby warns "already initialized constant" on the second eval for every
# top-level constant inside. Probe KEY_SIZE (first internal const) so a
# `defined?(EpsaStream)` race against the auto-defined-on-first-eval class
# doesn't false-positive.
unless defined?(EpsaStream::KEY_SIZE)

class EpsaStream
  class IntegrityError < StandardError; end
  class FormatError    < StandardError; end

  KEY_SIZE = 32

  # Per-duplicate plaintext LRU. PhysFS scans the ZIP central directory
  # with thousands of tiny reads at mount time — without this cache, every
  # one of them spun up a fresh OpenSSL::Cipher and re-decrypted an
  # AES-block-aligned slice, which dominated mount-time cost (measured 6.7s
  # for the Lethalmon archive). 64 KiB default chunk × 64 entries = ~4 MiB
  # ceiling per Io.
  PLAIN_CACHE_MAX = 64

  # Shared across duplicates of the same archive: which chunks we've already
  # MAC-verified, plus its mutex.
  VerifiedState = Struct.new(:set, :mutex)

  def initialize(path, enc_key:, mac_key:, _shared: nil)
    raise ArgumentError, "enc_key must be #{KEY_SIZE} bytes" unless enc_key.bytesize == KEY_SIZE
    raise ArgumentError, "mac_key must be #{KEY_SIZE} bytes" unless mac_key.bytesize == KEY_SIZE

    @path    = path
    @enc_key = enc_key.b
    @mac_key = mac_key.b
    @file    = File.open(path, 'rb')
    @offset  = 0

    parse_header_and_table!

    @verified    = _shared || VerifiedState.new(Set.new, Mutex.new)
    @plain_cache = {}   # k => frozen plaintext String
    @plain_lru   = []   # k order, oldest first
  end

  # PhysFS_Io duck type ------------------------------------------------------

  def length; @plaintext_len; end
  def tell;   @offset; end

  def seek(offset)
    raise RangeError, 'negative seek offset' if offset < 0
    @offset = offset
    self
  end

  # Returns up to `n` bytes; empty string at EOF. Mirrors the loose contract
  # PhysFS_Io.read uses (short reads near EOF are fine).
  def read(n)
    return ''.b if @offset >= @plaintext_len
    n = [@plaintext_len - @offset, n.to_i].min
    return ''.b if n <= 0

    out = String.new(encoding: Encoding::ASCII_8BIT, capacity: n)
    cur       = @offset
    remaining = n

    while remaining > 0
      k            = cur / @chunk_size
      plain        = chunk_plaintext(k)
      chunk_off    = k * @chunk_size
      in_chunk     = cur - chunk_off
      take         = [remaining, plain.bytesize - in_chunk].min

      out << plain.byteslice(in_chunk, take)
      cur       += take
      remaining -= take
    end

    @offset = cur
    out
  end

  def close
    @file.close unless @file.closed?
  end

  # PhysFS clones the IO for thread-safety: every PHYSFS_openRead in the
  # ZIP archiver calls io->duplicate(io). For the duplicate to actually be
  # safe to use concurrently with the original (and with sibling
  # duplicates), it MUST NOT share any mutable state with them — including
  # the verified-chunk cache. PhysFS only duplicates short-lived per-file
  # streams (the duplicate is destroyed when the file is closed), so the
  # cache savings of sharing are minimal anyway, while the cross-thread
  # contention on the shared Mutex was empirically deadlocking us when
  # compile and demo Ruby Threads each had their own duplicate.
  def duplicate
    EpsaStream.new(@path, enc_key: @enc_key, mac_key: @mac_key)
  end

  # PhysFS calls flush even on read-only IOs.
  def flush; nil; end

  # Internals ----------------------------------------------------------------

  private

  def parse_header_and_table!
    @file.seek(0)
    raw = @file.read(EpsaFormat::HEADER_SIZE)
    raise FormatError, 'archive smaller than header' if raw.nil? || raw.bytesize < EpsaFormat::HEADER_SIZE

    raise FormatError, 'magic mismatch' unless raw.byteslice(0, 4) == EpsaFormat::MAGIC
    version = raw.byteslice(4, 4).unpack1('V')
    raise FormatError, "unsupported version: #{version} (expected #{EpsaFormat::VERSION})" \
      unless version == EpsaFormat::VERSION

    @kdf_version   = raw.getbyte(EpsaFormat::KDF_VERSION_OFFSET)
    reserved       = raw.byteslice(EpsaFormat::RESERVED_OFFSET, 3)
    raise FormatError, 'reserved bytes nonzero' unless reserved == "\x00\x00\x00".b

    @build_id      = raw.byteslice(EpsaFormat::BUILD_ID_OFFSET, EpsaFormat::BUILD_ID_SIZE)
    @nonce         = raw.byteslice(EpsaFormat::NONCE_OFFSET, EpsaFormat::NONCE_SIZE)
    @chunk_log2    = raw.byteslice(EpsaFormat::CHUNK_LOG2_OFFSET, 4).unpack1('V')
    @plaintext_len = raw.byteslice(EpsaFormat::PLAINTEXT_LEN_OFFSET, 8).unpack1('Q<')

    raise FormatError, "chunk_log2 out of range: #{@chunk_log2}" unless (4..30).cover?(@chunk_log2)

    @chunk_size = 1 << @chunk_log2
    @n_chunks   = (@plaintext_len + @chunk_size - 1) / @chunk_size

    expected_size = EpsaFormat::HEADER_SIZE + @n_chunks * EpsaFormat::HMAC_SIZE + @plaintext_len
    actual_size   = @file.size
    raise FormatError, "archive size #{actual_size} != expected #{expected_size}" \
      unless actual_size == expected_size

    table_bytes = @n_chunks * EpsaFormat::HMAC_SIZE
    @hmac_table = table_bytes.zero? ? ''.b : @file.read(table_bytes)
    raise FormatError, 'truncated HMAC table' if @hmac_table.bytesize != table_bytes

    @ctxt_base = EpsaFormat::HEADER_SIZE + table_bytes
  end

  def chunk_len(k)
    [@chunk_size, @plaintext_len - k * @chunk_size].min
  end

  def chunk_file_offset(k)
    @ctxt_base + k * @chunk_size
  end

  def stored_hmac(k)
    @hmac_table.byteslice(k * EpsaFormat::HMAC_SIZE, EpsaFormat::HMAC_SIZE)
  end

  # Return the full plaintext for chunk `k`, decrypting (and verifying, if
  # this Io hasn't seen it yet) on a cache miss. Folds the previously
  # separate verify+slice-decrypt steps: each chunk is now read once,
  # HMAC'd once, decrypted once via a single OpenSSL call, and reused for
  # every subsequent read that lands in the same chunk. The cache is
  # bounded by PLAIN_CACHE_MAX entries (per Io, never shared).
  def chunk_plaintext(k)
    cached = @plain_cache[k]
    if cached
      touch_lru(k)
      return cached
    end

    ctxt = pread(chunk_file_offset(k), chunk_len(k))
    raise IntegrityError, "short read on chunk #{k}" if ctxt.bytesize != chunk_len(k)

    # HMAC once per chunk across this Io's lifetime; cache eviction (LRU)
    # may force a re-decrypt but never a re-verify.
    already_verified = nil
    @verified.mutex.synchronize { already_verified = @verified.set.include?(k) }
    unless already_verified
      computed = OpenSSL::HMAC.digest('SHA256', @mac_key, [k].pack('Q<') + ctxt)
      expected = stored_hmac(k)
      unless OpenSSL.fixed_length_secure_compare(computed, expected)
        raise IntegrityError, "HMAC mismatch for chunk #{k}"
      end
      @verified.mutex.synchronize { @verified.set.add(k) }
    end

    # One OpenSSL::Cipher allocation per chunk instead of per read — this
    # is the win over the old `decrypt_slice`. iv_for_block(nonce, k*N) is
    # equivalent to iv_for_chunk(nonce, k, chunk_size).
    iv = EpsaFormat.iv_for_block(@nonce, k * (@chunk_size / EpsaFormat::AES_BLOCK_SIZE))
    cipher = OpenSSL::Cipher.new('aes-256-ctr')
    cipher.decrypt
    cipher.key = @enc_key
    cipher.iv  = iv
    plain = (cipher.update(ctxt) + cipher.final).freeze

    @plain_cache[k] = plain
    @plain_lru << k
    while @plain_lru.size > PLAIN_CACHE_MAX
      @plain_cache.delete(@plain_lru.shift)
    end

    plain
  end

  def touch_lru(k)
    return if @plain_lru.last == k
    @plain_lru.delete(k)
    @plain_lru << k
  end

  def pread(off, len)
    @file.seek(off)
    @file.read(len) || ''.b
  end
end

end # unless defined?(EpsaStream::KEY_SIZE)
