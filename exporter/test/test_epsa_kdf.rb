# Parity test between the exporter's EpsaKdf and the app's native KDF
# (app/src/main/cpp/test_epsa_kdf.c): same inputs, same expected key. A
# change to one side's salt, info or HKDF breaks one of the two tests.
#
# Run:  ruby exporter/test/test_epsa_kdf.rb

require 'minitest/autorun'

require_relative '../lib/epsa_kdf'

class EpsaKdfTest < Minitest::Test
  # Test inputs — must match app/src/main/cpp/test_epsa_kdf.c.
  TEST_CERT        = ['34080a5acf49c2e3630ca1158114b0c882fe6517078575c06491ee00d4c0eac3'].pack('H*')
  TEST_BUILD_ID    = ['3ae9f9681bb4459c'].pack('H*')
  TEST_KDF_VERSION = 1
  EXPECTED_KEY_HEX = '2b7d74cd611eb268e5d90bbdc03413f99151b252c0ea959c39d3eab238a03b22'

  def test_hkdf_matches_native
    info = EpsaKdf.deobfuscate(EpsaKdf::INFO_XOR) + TEST_KDF_VERSION.chr + TEST_BUILD_ID
    key = EpsaKdf.hkdf_sha256(ikm: TEST_CERT, salt: EpsaKdf.deobfuscate(EpsaKdf::SALT_XOR), info: info,
                              length: EpsaKdf::KEY_SIZE)
    assert_equal EXPECTED_KEY_HEX, key.unpack1('H*')
  end

  def test_v4_keys_are_distinct
    keys = EpsaKdf.derive_v4(TEST_CERT, TEST_BUILD_ID, TEST_KDF_VERSION)
    refute_equal keys[:enc_key], keys[:mac_key]
    assert_equal [EpsaKdf::KEY_SIZE] * 2, keys.values.map(&:bytesize)
  end
end
