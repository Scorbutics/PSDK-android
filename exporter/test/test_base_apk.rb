# Tests of lib/base_apk.rb against a fake GitHub (releases API and asset
# downloads, behind a redirect like GitHub's), served over local HTTP.
#
# Run:  ruby exporter/test/test_base_apk.rb

require 'minitest/autorun'
require 'digest'
require 'fileutils'
require 'json'
require 'socket'
require 'tmpdir'

require_relative '../lib/base_apk'

class BaseApkTest < Minitest::Test
  # A local HTTP server answering from @routes (path => [status, headers, body]),
  # counting the requests per path in @hits.
  def setup
    @dir = Dir.mktmpdir('base_apk_test')
    @cache = File.join(@dir, 'cache')
    @routes = {}
    @hits = Hash.new(0)
    @server = TCPServer.new('127.0.0.1', 0)
    @base = "http://127.0.0.1:#{@server.addr[1]}"
    @thread = Thread.new { loop { serve(@server.accept) } }
  end

  def teardown
    @thread.kill
    @server.close
    FileUtils.rm_rf(@dir)
  end

  def serve(client)
    path = client.gets.to_s.split[1]
    nil while (line = client.gets) && line != "\r\n"
    @hits[path] += 1
    status, headers, body = @routes.fetch(path, [404, {}, 'Not Found'])
    head = headers.merge('Content-Length' => body.bytesize, 'Connection' => 'close').map { |key, value| "#{key}: #{value}\r\n" }
    client.write("HTTP/1.1 #{status} X\r\n#{head.join}\r\n#{body}")
  ensure
    client.close
  end

  # A release `tag` with APKs for `rubies`, as the latest release unless
  # `latest: false`. Returns the APK contents by name.
  def publish(tag, rubies, latest: true, digest: true)
    apks = rubies.to_h { |ruby| [BaseApk.apk_name(ruby), "apk #{tag} #{ruby}"] }
    assets = apks.map do |name, content|
      # GitHub redirects a download to another host
      @routes["/download/#{tag}/#{name}"] = [302, { 'Location' => "/objects/#{tag}/#{name}" }, '']
      @routes["/objects/#{tag}/#{name}"] = [200, {}, content]
      asset = { 'name' => name, 'size' => content.bytesize, 'browser_download_url' => "#{@base}/download/#{tag}/#{name}" }
      asset['digest'] = "sha256:#{Digest::SHA256.hexdigest(content)}" if digest
      asset
    end
    release = JSON.generate('tag_name' => tag, 'assets' => assets)
    @routes["/releases/tags/#{tag}"] = [200, {}, release]
    @routes['/releases/latest'] = [200, {}, release] if latest
    apks
  end

  def fetch(rubies, tag: nil, api_url: "#{@base}/releases")
    BaseApk.fetch(rubies, tag: tag, cache_dir: @cache, api_url: api_url)
  end

  def test_downloads_the_latest_release_once
    apks = publish('v1.6.0', %w[3.0 3.3])
    path, tag = nil
    out, = capture_io { path, tag = fetch(%w[3.3 3.0]) }
    assert_equal 'v1.6.0', tag
    assert_equal File.join(@cache, 'v1.6.0', 'PSDK-android-ruby3.3-release.apk'), path
    assert_equal apks['PSDK-android-ruby3.3-release.apk'], File.binread(path)
    assert_match(/Downloading the base APK PSDK-android-ruby3\.3-release\.apk \(v1\.6\.0/, out)

    out, = capture_io { assert_equal [path, 'v1.6.0'], fetch(%w[3.3 3.0]) }
    assert_empty out
    assert_equal 1, @hits['/objects/v1.6.0/PSDK-android-ruby3.3-release.apk']
    assert_equal 2, @hits['/releases/latest'], 'every export asks for the latest release'
  end

  def test_falls_back_on_the_next_ruby
    publish('v1.6.0', %w[3.0])
    capture_io { assert_equal 'PSDK-android-ruby3.0-release.apk', File.basename(fetch(%w[4.0 3.0]).first) }
  end

  def test_a_new_release_replaces_the_cached_one
    publish('v1.6.0', %w[3.0])
    capture_io { fetch(%w[3.0]) }
    apks = publish('v1.7.0', %w[3.0])
    path = nil
    capture_io { path, = fetch(%w[3.0]) }
    assert_equal apks['PSDK-android-ruby3.0-release.apk'], File.binread(path)
    assert_equal ['v1.7.0'], Dir.children(@cache)
  end

  def test_a_broken_cached_apk_is_downloaded_again
    apks = publish('v1.6.0', %w[3.0])
    path = File.join(@cache, 'v1.6.0', 'PSDK-android-ruby3.0-release.apk')
    FileUtils.mkdir_p(File.dirname(path))
    File.binwrite(path, 'truncated')
    capture_io { fetch(%w[3.0]) }
    assert_equal apks['PSDK-android-ruby3.0-release.apk'], File.binread(path)
  end

  def test_a_pinned_release_keeps_the_others
    publish('v1.7.0', %w[3.0])
    publish('v1.6.0', %w[3.0], latest: false)
    capture_io { fetch(%w[3.0]) }
    path, tag = nil
    capture_io { path, tag = fetch(%w[3.0], tag: 'v1.6.0') }
    assert_equal 'v1.6.0', tag
    assert_equal 'apk v1.6.0 3.0', File.binread(path)
    assert_equal %w[v1.6.0 v1.7.0], Dir.children(@cache).sort
  end

  def test_an_unknown_release
    error = assert_raises(BaseApk::Error) { fetch(%w[3.0], tag: 'v9.9.9') }
    assert_match(/no release v9\.9\.9/, error.message)
  end

  def test_no_apk_for_the_ruby
    publish('v1.6.0', %w[3.0 3.2])
    error = assert_raises(BaseApk::Error) { fetch(%w[3.3]) }
    assert_match(/v1\.6\.0 has no APK for Ruby 3\.3 \(3\.0, 3\.2 only\)/, error.message)
  end

  def test_a_corrupted_download_is_not_kept
    publish('v1.6.0', %w[3.0])
    @routes['/objects/v1.6.0/PSDK-android-ruby3.0-release.apk'][2] = 'apk v1.6.0 3.X'
    error = assert_raises(BaseApk::Error) { capture_io { fetch(%w[3.0]) } }
    assert_match(/sha256 digest mismatch/, error.message)
    assert_empty Dir.glob('**/*.apk*', base: @cache)
  end

  def test_offline_uses_the_newest_cached_release
    publish('v1.6.0', %w[3.0])
    capture_io { fetch(%w[3.0]) }
    # Others left by pinned exports
    FileUtils.mkdir_p(File.join(@cache, 'v1.10.0'))
    File.binwrite(File.join(@cache, 'v1.10.0', 'PSDK-android-ruby3.0-release.apk'), 'apk v1.10.0 3.0')
    FileUtils.mkdir_p(File.join(@cache, 'v1.9.0'))
    File.binwrite(File.join(@cache, 'v1.9.0', 'PSDK-android-ruby3.0-release.apk'), 'apk v1.9.0 3.0')
    offline = 'http://127.0.0.1:9/releases'
    path, tag = nil
    _, err = capture_io { path, tag = fetch(%w[3.0], api_url: offline) }
    assert_equal 'v1.10.0', tag, 'versions sort as numbers'
    assert_equal 'apk v1.10.0 3.0', File.binread(path)
    assert_match(/can't be reached .*: using the downloaded base APK of v1\.10\.0/, err)

    error = assert_raises(BaseApk::Error) { fetch(%w[3.3], api_url: offline) }
    assert_match(/No base APK downloaded yet, and .* can't be reached/, error.message)
  end
end
