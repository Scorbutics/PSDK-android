# Signs an APK with APK Signature Scheme v2: what apksigner did, with the
# key and the certificate of the PKCS12 keystore (see pkcs12_cert.rb).
#
# v2 alone is enough for the PSDK player (minSdk 26 > 24, the last release
# without v2). A v3 block would only add key rotation.
#
# Spec: https://source.android.com/docs/security/features/apksigning/v2
# Block layout: see apk_signing_block.rb.
#
# What's signed: the SHA-256 digest of the APK's three sections (the ZIP
# entries, the central directory, and the EOCD as if the central directory
# started where the signing block starts), each cut in 1 MiB chunks:
#   chunk digest:  SHA-256(0xa5, uint32 chunk size, chunk)
#   APK digest:    SHA-256(0x5a, uint32 chunk count, chunk digests...)
#
# The v2 block value (every "lp" is a uint32 length prefix):
#   lp(signers: lp(signer))
#   signer:      lp(signed data), lp(signatures: lp(uint32 algorithm, lp(signature))), lp(public key)
#   signed data: lp(digests: lp(uint32 algorithm, lp(digest))), lp(certificates: lp(DER)),
#                lp(additional attributes: none)
#
# All multi-byte fields are little-endian.

require 'openssl'

module ApkV2Signer
  module_function

  SigningError = Class.new(StandardError)

  V2_BLOCK_ID      = 0x7109871a
  MAGIC            = 'APK Sig Block 42'
  CHUNK_SIZE       = 1 << 20
  RSA_PKCS1_SHA256 = 0x0103
  ECDSA_SHA256     = 0x0201

  # Writes the signing block, the central directory and the EOCD after the
  # ZIP entries that `io` (opened for reading and writing) holds up to
  # `cd_offset` (see ApkZip.write).
  #
  # @param key  [OpenSSL::PKey::RSA, OpenSSL::PKey::EC] the keystore's private key
  # @param cert [OpenSSL::X509::Certificate] its certificate
  def sign(io, cd_offset:, central_directory:, eocd:, key:, cert:)
    algorithm = case key
                when OpenSSL::PKey::RSA then RSA_PKCS1_SHA256
                when OpenSSL::PKey::EC then ECDSA_SHA256
                else raise SigningError, "Unsupported signing key: #{key.class} (RSA or EC only)"
                end
    raise SigningError, "The keystore's key doesn't match its certificate" unless cert.check_private_key(key)

    digest = apk_digest(io, cd_offset, central_directory, eocd)
    signed_data = lp(lp([algorithm].pack('V') + lp(digest))) + lp(lp(cert.to_der)) + lp('')
    signature = key.sign(OpenSSL::Digest.new('SHA256'), signed_data)
    signer = lp(signed_data) + lp(lp([algorithm].pack('V') + lp(signature))) + lp(public_key_der(cert))
    value = lp(lp(signer))

    pair = [4 + value.bytesize].pack('Q<') + [V2_BLOCK_ID].pack('V') + value
    block_size = pair.bytesize + 8 + MAGIC.bytesize
    block = [block_size].pack('Q<') + pair + [block_size].pack('Q<') + MAGIC

    signed_eocd = eocd.dup
    signed_eocd[16, 4] = [cd_offset + block.bytesize].pack('V')
    io.seek(cd_offset)
    io.write(block, central_directory, signed_eocd)
    io.truncate(io.pos)
  end

  def apk_digest(io, cd_offset, central_directory, eocd)
    chunk_digests = []
    io.seek(0)
    remaining = cd_offset
    while remaining.positive?
      chunk = io.read([remaining, CHUNK_SIZE].min)
      chunk_digests << chunk_digest(chunk)
      remaining -= chunk.bytesize
    end
    [central_directory, eocd].each do |section|
      (0...section.bytesize).step(CHUNK_SIZE) { |start| chunk_digests << chunk_digest(section.byteslice(start, CHUNK_SIZE)) }
    end
    OpenSSL::Digest::SHA256.digest("\x5a".b + [chunk_digests.size].pack('V') + chunk_digests.join)
  end

  def chunk_digest(chunk)
    OpenSSL::Digest::SHA256.digest("\xa5".b + [chunk.bytesize].pack('V') + chunk)
  end

  # The certificate's SubjectPublicKeyInfo, as the signer's public key.
  # Taken from the certificate's own fields: PKey#public_to_der doesn't
  # exist before the openssl gem 3.0 (Ruby 3.1).
  def public_key_der(cert)
    fields = OpenSSL::ASN1.decode(cert.to_der).value.first.value
    # A v3 certificate starts with an explicit [0] version.
    fields = fields.drop(1) if fields.first.tag_class == :CONTEXT_SPECIFIC
    fields[5].to_der
  end

  def lp(bytes)
    [bytes.bytesize].pack('V') + bytes.b
  end
end
