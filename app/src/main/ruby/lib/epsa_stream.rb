# Streaming reader for v4 .epsa archives.
#
# Implements the duck-typed IO surface that PhysFS.mount_io expects: read,
# seek, tell, length, close, duplicate, flush. Decrypts on demand chunk by
# chunk so the plaintext archive never lands on disk.
#
# Threading: PhysFS may call duplicate() and use the clones from different
# threads concurrently. Each duplicate has its own file handle and current
# offset; the verified-chunk set is shared across duplicates and protected by
# a Mutex so we don't re-verify chunks that another duplicate already MAC'd.

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

class EpsaStream
  class IntegrityError < StandardError; end
  class FormatError    < StandardError; end

  KEY_SIZE = 32

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

    @verified = _shared || VerifiedState.new(Set.new, Mutex.new)
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
      k          = cur / @chunk_size
      ensure_verified!(k)

      chunk_off    = k * @chunk_size
      chunk_size_k = chunk_len(k)
      in_chunk     = cur - chunk_off
      take         = [remaining, chunk_size_k - in_chunk].min

      out << decrypt_slice(k, in_chunk, take)
      cur       += take
      remaining -= take
    end

    @offset = cur
    out
  end

  def close
    @file.close unless @file.closed?
  end

  # PhysFS clones the IO for thread-safety. Fresh fd, independent offset,
  # but shared verified-chunks state to avoid duplicate MAC work.
  def duplicate
    EpsaStream.new(@path, enc_key: @enc_key, mac_key: @mac_key, _shared: @verified)
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

  def ensure_verified!(k)
    @verified.mutex.synchronize do
      return if @verified.set.include?(k)
    end

    ctxt = pread(chunk_file_offset(k), chunk_len(k))
    raise IntegrityError, "short read on chunk #{k}" if ctxt.bytesize != chunk_len(k)

    computed = OpenSSL::HMAC.digest('SHA256', @mac_key, [k].pack('Q<') + ctxt)
    expected = stored_hmac(k)

    unless OpenSSL.fixed_length_secure_compare(computed, expected)
      raise IntegrityError, "HMAC mismatch for chunk #{k}"
    end

    @verified.mutex.synchronize { @verified.set.add(k) }
  end

  # Decrypt `length` bytes starting at `in_chunk_offset` within chunk `k`.
  # Reads only the block-aligned ciphertext slice from disk — no full-chunk
  # decrypt unless the read genuinely spans the whole chunk.
  def decrypt_slice(k, in_chunk_offset, length)
    block_start = in_chunk_offset / EpsaFormat::AES_BLOCK_SIZE
    block_end   = (in_chunk_offset + length + EpsaFormat::AES_BLOCK_SIZE - 1) /
                  EpsaFormat::AES_BLOCK_SIZE

    ctxt_start_in_chunk  = block_start * EpsaFormat::AES_BLOCK_SIZE
    ctxt_end_in_chunk    = [block_end * EpsaFormat::AES_BLOCK_SIZE, chunk_len(k)].min
    ctxt_len             = ctxt_end_in_chunk - ctxt_start_in_chunk

    ctxt = pread(chunk_file_offset(k) + ctxt_start_in_chunk, ctxt_len)

    blocks_in_prev_chunks = k * (@chunk_size / EpsaFormat::AES_BLOCK_SIZE)
    iv = EpsaFormat.iv_for_block(@nonce, blocks_in_prev_chunks + block_start)

    cipher = OpenSSL::Cipher.new('aes-256-ctr')
    cipher.decrypt
    cipher.key = @enc_key
    cipher.iv  = iv
    plaintext_block_aligned = cipher.update(ctxt) + cipher.final

    inner_offset = in_chunk_offset - ctxt_start_in_chunk
    plaintext_block_aligned.byteslice(inner_offset, length)
  end

  def pread(off, len)
    @file.seek(off)
    @file.read(len) || ''.b
  end
end
