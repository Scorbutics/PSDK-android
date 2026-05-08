# Unit tests for EpsaStream — the consumer-side streaming decrypter.
#
# A small inline producer (TestArchiveBuilder) writes v4 archives with
# explicit enc_key/mac_key/nonce so each test is fully deterministic and
# doesn't depend on the cross-repo EpsaWriter / EpsaKdf code path.
#
# epsa_stream.rb deliberately does NOT `require_relative 'epsa_format'`
# (see comment in that file), so this test loads epsa_format first
# explicitly. On Android the same load order is enforced by
# CompilationEngine.ARCHIVE_PRELUDES.
#
# Run:  ruby app/src/test/ruby/test_epsa_stream.rb

require 'minitest/autorun'
require 'tempfile'
require 'openssl'

LIB = File.expand_path('../../main/ruby/lib', __dir__)
require File.join(LIB, 'epsa_format')
require File.join(LIB, 'epsa_stream')

# Minimal v4 producer for tests. Mirrors PSDKTechnicalDemo's EpsaWriter but
# takes keys directly instead of running HKDF — we want to exercise
# EpsaStream's parsing and verification logic, not KDF parity.
module TestArchiveBuilder
  module_function

  def build(path:, plaintext:, enc_key:, mac_key:,
            kdf_version: 1, build_id: "\x00" * 8, nonce: nil,
            chunk_log2: 8)
    nonce ||= "\x00".b * EpsaFormat::NONCE_SIZE
    chunk_size    = 1 << chunk_log2
    plaintext_len = plaintext.bytesize
    n_chunks      = (plaintext_len + chunk_size - 1) / chunk_size

    hmac_table = String.new(encoding: Encoding::ASCII_8BIT)
    ctxt_blob  = String.new(encoding: Encoding::ASCII_8BIT)

    n_chunks.times do |k|
      ptxt = plaintext.byteslice(k * chunk_size, chunk_size) || ''.b
      iv   = EpsaFormat.iv_for_chunk(nonce, k, chunk_size)

      cipher = OpenSSL::Cipher.new('aes-256-ctr')
      cipher.encrypt
      cipher.key = enc_key
      cipher.iv  = iv
      ctxt = cipher.update(ptxt) + cipher.final

      hmac = OpenSSL::HMAC.digest('SHA256', mac_key, [k].pack('Q<') + ctxt)
      hmac_table << hmac
      ctxt_blob  << ctxt
    end

    File.open(path, 'wb') do |f|
      f.write(EpsaFormat::MAGIC)
      f.write([EpsaFormat::VERSION].pack('V'))
      f.write([kdf_version].pack('C'))
      f.write("\x00\x00\x00".b)
      f.write(build_id)
      f.write(nonce)
      f.write([chunk_log2].pack('V'))
      f.write([plaintext_len].pack('Q<'))
      f.write(hmac_table)
      f.write(ctxt_blob)
    end

    n_chunks
  end
end

class EpsaStreamTest < Minitest::Test
  ENC_KEY = ("\xa5".b * 32).freeze
  MAC_KEY = ("\x5a".b * 32).freeze
  CHUNK_LOG2 = 8        # 256-byte chunks
  CHUNK_SIZE = 1 << CHUNK_LOG2

  def with_archive(plaintext, **opts)
    Tempfile.create(['epsa_stream_test', '.epsa'], binmode: true) do |f|
      f.close
      TestArchiveBuilder.build(
        path: f.path,
        plaintext: plaintext.b,
        enc_key: ENC_KEY,
        mac_key: MAC_KEY,
        chunk_log2: CHUNK_LOG2,
        **opts
      )
      stream = EpsaStream.new(f.path, enc_key: ENC_KEY, mac_key: MAC_KEY)
      begin
        yield stream, f.path
      ensure
        stream.close
      end
    end
  end

  # ---------- basic IO surface ----------

  def test_length_matches_plaintext
    pt = OpenSSL::Random.random_bytes(1234)
    with_archive(pt) { |s| assert_equal 1234, s.length }
  end

  def test_initial_tell_is_zero
    with_archive('hi') { |s| assert_equal 0, s.tell }
  end

  def test_full_read_round_trips
    pt = OpenSSL::Random.random_bytes(3 * CHUNK_SIZE + 17)
    with_archive(pt) do |s|
      got = s.read(s.length)
      assert_equal pt, got
      assert_equal pt.bytesize, s.tell
    end
  end

  def test_read_zero_returns_empty
    with_archive('hello') { |s| assert_equal ''.b, s.read(0) }
  end

  def test_read_past_eof_returns_empty
    with_archive('hi') do |s|
      s.read(s.length)
      assert_equal ''.b, s.read(100)
    end
  end

  def test_read_short_at_eof
    pt = 'abcdef'.b
    with_archive(pt) do |s|
      s.seek(4)
      got = s.read(100)
      assert_equal 'ef'.b, got
      assert_equal 6, s.tell
    end
  end

  # ---------- prelude-execution sanity ----------
  # Mirrors how the lib/*.rb assets are run on Android: their content is
  # concatenated into the script body and passed as a string to the VM.
  # This catches any future `require_relative` (or other __FILE__-dependent
  # construct) that would silently break the production load path.

  def test_prelude_concatenation_runs_cleanly
    sources = %w[epsa_format.rb epsa_stream.rb archive_mount.rb].map do |name|
      File.read(File.join(LIB, name))
    end
    blob = sources.join("\n")
    # Run in a subprocess so the parent VM's already-loaded constants
    # don't mask a missing require.
    Tempfile.create(['concat_test', '.rb']) do |f|
      f.write(<<~RUBY)
        eval(<<'CONCATENATED_PRELUDE', binding, '(epsa-prelude)', 1)
        #{blob}
        CONCATENATED_PRELUDE
        raise 'EpsaFormat missing'  unless defined?(EpsaFormat) == 'constant'
        raise 'EpsaStream missing'  unless defined?(EpsaStream) == 'constant'
        raise 'ArchiveMount missing' unless defined?(ArchiveMount) == 'constant'
        puts 'prelude ok'
      RUBY
      f.close
      output = `ruby #{f.path} 2>&1`
      assert_equal 0, $?.exitstatus,
                   "Prelude eval failed:\n#{output}"
      assert_match(/prelude ok/, output)
    end
  end

  # ---------- seek + partial reads ----------

  def test_seek_then_read
    pt = (0..255).map(&:chr).join.b * 4
    with_archive(pt) do |s|
      s.seek(500)
      got = s.read(50)
      assert_equal pt.byteslice(500, 50), got
      assert_equal 550, s.tell
    end
  end

  def test_unaligned_read_within_chunk
    pt = OpenSSL::Random.random_bytes(CHUNK_SIZE)
    with_archive(pt) do |s|
      s.seek(7)
      got = s.read(13)
      assert_equal pt.byteslice(7, 13), got
    end
  end

  def test_read_spanning_chunk_boundary
    pt = OpenSSL::Random.random_bytes(3 * CHUNK_SIZE)
    with_archive(pt) do |s|
      s.seek(CHUNK_SIZE - 5)
      got = s.read(CHUNK_SIZE + 10)
      assert_equal pt.byteslice(CHUNK_SIZE - 5, CHUNK_SIZE + 10), got
    end
  end

  def test_read_spanning_three_chunks
    pt = OpenSSL::Random.random_bytes(5 * CHUNK_SIZE + 7)
    with_archive(pt) do |s|
      s.seek(CHUNK_SIZE / 2)
      got = s.read(2 * CHUNK_SIZE + 10)
      assert_equal pt.byteslice(CHUNK_SIZE / 2, 2 * CHUNK_SIZE + 10), got
    end
  end

  def test_random_reads_consistent_with_full
    pt = OpenSSL::Random.random_bytes(4 * CHUNK_SIZE + 99)
    with_archive(pt) do |s|
      rng = Random.new(0xdead)
      30.times do
        off = rng.rand(pt.bytesize)
        len = rng.rand(pt.bytesize - off + 1)
        s.seek(off)
        got = s.read(len)
        assert_equal pt.byteslice(off, len), got, "mismatch at off=#{off} len=#{len}"
      end
    end
  end

  def test_negative_seek_raises
    with_archive('x') do |s|
      assert_raises(RangeError) { s.seek(-1) }
    end
  end

  # ---------- format validation ----------

  def test_bad_magic_raises_format_error
    Tempfile.create(['bad_magic', '.epsa'], binmode: true) do |f|
      f.close
      File.binwrite(f.path, 'XXXX' + ("\x00".b * 100))
      assert_raises(EpsaStream::FormatError) do
        EpsaStream.new(f.path, enc_key: ENC_KEY, mac_key: MAC_KEY)
      end
    end
  end

  def test_bad_version_raises
    with_archive('hello') do |_s, path|
      raw = File.binread(path)
      raw[4, 4] = [99].pack('V')
      File.binwrite(path, raw)
      assert_raises(EpsaStream::FormatError) do
        EpsaStream.new(path, enc_key: ENC_KEY, mac_key: MAC_KEY)
      end
    end
  end

  def test_size_mismatch_raises
    with_archive('hello') do |_s, path|
      File.open(path, 'a') { |f| f.write('extra') }
      assert_raises(EpsaStream::FormatError) do
        EpsaStream.new(path, enc_key: ENC_KEY, mac_key: MAC_KEY)
      end
    end
  end

  def test_short_key_raises
    with_archive('hello') do |_s, path|
      assert_raises(ArgumentError) do
        EpsaStream.new(path, enc_key: 'short', mac_key: MAC_KEY)
      end
    end
  end

  # ---------- integrity ----------

  def test_tampered_ciphertext_raises_on_read
    with_archive(('A' * (2 * CHUNK_SIZE)).b) do |_s, path|
      raw = File.binread(path)
      n_chunks = 2
      flip = EpsaFormat::HEADER_SIZE + n_chunks * EpsaFormat::HMAC_SIZE + 10
      raw.setbyte(flip, raw.getbyte(flip) ^ 0x01)
      File.binwrite(path, raw)

      stream = EpsaStream.new(path, enc_key: ENC_KEY, mac_key: MAC_KEY)
      begin
        assert_raises(EpsaStream::IntegrityError) { stream.read(stream.length) }
      ensure
        stream.close
      end
    end
  end

  def test_tampered_hmac_raises_on_read
    with_archive('hello world'.b) do |_s, path|
      raw = File.binread(path)
      raw.setbyte(EpsaFormat::HEADER_SIZE,
                  raw.getbyte(EpsaFormat::HEADER_SIZE) ^ 0xff)
      File.binwrite(path, raw)

      stream = EpsaStream.new(path, enc_key: ENC_KEY, mac_key: MAC_KEY)
      begin
        assert_raises(EpsaStream::IntegrityError) { stream.read(stream.length) }
      ensure
        stream.close
      end
    end
  end

  def test_wrong_mac_key_raises
    with_archive('payload'.b) do |_s, path|
      wrong_mac = ("\x00".b * 32)
      stream = EpsaStream.new(path, enc_key: ENC_KEY, mac_key: wrong_mac)
      begin
        assert_raises(EpsaStream::IntegrityError) { stream.read(stream.length) }
      ensure
        stream.close
      end
    end
  end

  # ---------- duplicate ----------

  def test_duplicate_independent_offset
    pt = OpenSSL::Random.random_bytes(CHUNK_SIZE * 2)
    with_archive(pt) do |s|
      d = s.duplicate
      begin
        s.seek(0)
        d.seek(100)
        a = s.read(50)
        b = d.read(50)
        assert_equal pt.byteslice(0, 50),   a
        assert_equal pt.byteslice(100, 50), b
        assert_equal 50,  s.tell
        assert_equal 150, d.tell
      ensure
        d.close
      end
    end
  end

  def test_duplicate_round_trips_full_archive
    pt = OpenSSL::Random.random_bytes(3 * CHUNK_SIZE + 13)
    with_archive(pt) do |s|
      d = s.duplicate
      begin
        assert_equal pt, d.read(d.length)
      ensure
        d.close
      end
    end
  end

  def test_duplicate_shares_verified_state
    pt = OpenSSL::Random.random_bytes(2 * CHUNK_SIZE)
    with_archive(pt) do |s|
      d1 = s.duplicate
      d2 = s.duplicate
      begin
        assert_equal pt, d1.read(d1.length)
        assert_equal pt, d2.read(d2.length)
      ensure
        d1.close
        d2.close
      end
    end
  end

  # ---------- nonce / counter math edge cases ----------

  def test_nonzero_nonce_round_trips
    pt = OpenSSL::Random.random_bytes(CHUNK_SIZE * 3 + 5)
    nonce = OpenSSL::Random.random_bytes(EpsaFormat::NONCE_SIZE)
    Tempfile.create(['nonce_test', '.epsa'], binmode: true) do |f|
      f.close
      TestArchiveBuilder.build(
        path: f.path, plaintext: pt, enc_key: ENC_KEY, mac_key: MAC_KEY,
        chunk_log2: CHUNK_LOG2, nonce: nonce
      )
      stream = EpsaStream.new(f.path, enc_key: ENC_KEY, mac_key: MAC_KEY)
      begin
        assert_equal pt, stream.read(stream.length)
      ensure
        stream.close
      end
    end
  end

  def test_high_nonce_does_not_break_arithmetic
    nonce = ("\xff".b * 14) + "\x00\x00".b
    pt = OpenSSL::Random.random_bytes(2 * CHUNK_SIZE)
    Tempfile.create(['high_nonce', '.epsa'], binmode: true) do |f|
      f.close
      TestArchiveBuilder.build(
        path: f.path, plaintext: pt, enc_key: ENC_KEY, mac_key: MAC_KEY,
        chunk_log2: CHUNK_LOG2, nonce: nonce
      )
      stream = EpsaStream.new(f.path, enc_key: ENC_KEY, mac_key: MAC_KEY)
      begin
        assert_equal pt, stream.read(stream.length)
      ensure
        stream.close
      end
    end
  end

  # ---------- empty archive ----------

  def test_empty_payload_round_trips
    with_archive(''.b) do |s|
      assert_equal 0, s.length
      assert_equal ''.b, s.read(100)
    end
  end
end
