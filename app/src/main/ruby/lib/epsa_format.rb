# EPSA archive format constants — consumer-side mirror of
# PSDKTechnicalDemo/plugins/epsa_format.rb. The two files MUST stay in sync;
# the v4 spec is documented there. Any change to magic / header layout / KDF
# tag strings has to land in both repos in lockstep.

module EpsaFormat
  MAGIC                    = 'PSAE'
  VERSION                  = 4
  HEADER_SIZE              = 48
  KDF_VERSION_OFFSET       = 8
  RESERVED_OFFSET          = 9
  BUILD_ID_OFFSET          = 12
  NONCE_OFFSET             = 20
  CHUNK_LOG2_OFFSET        = 36
  PLAINTEXT_LEN_OFFSET     = 40

  BUILD_ID_SIZE            = 8
  NONCE_SIZE               = 16
  HMAC_SIZE                = 32

  AES_BLOCK_SIZE           = 16

  # CTR counter arithmetic. The 16-byte nonce in the header is the initial
  # counter block; OpenSSL's aes-256-ctr increments it per AES block as a
  # big-endian uint128.
  def self.advance_be128(nonce_bytes, increment)
    raise ArgumentError, "expected #{NONCE_SIZE} bytes" unless nonce_bytes.bytesize == NONCE_SIZE
    v = (nonce_bytes.unpack1('H*').to_i(16) + increment) & ((1 << 128) - 1)
    [v.to_s(16).rjust(NONCE_SIZE * 2, '0')].pack('H*')
  end

  def self.iv_for_block(nonce_bytes, block_index)
    advance_be128(nonce_bytes, block_index)
  end

  def self.iv_for_chunk(nonce_bytes, chunk_index, chunk_size)
    advance_be128(nonce_bytes, chunk_index * (chunk_size / AES_BLOCK_SIZE))
  end
end
