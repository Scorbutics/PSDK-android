# The base APK of an APK export run without --apk: a release APK of
# PSDK-android, downloaded from GitHub and cached in ~/.psdk/android-apk/.
#
# Every export asks GitHub for the latest release (or a given tag) and
# downloads its APK the first time only: a new release replaces the cached
# one. Offline, or when GitHub doesn't answer, the newest APK already cached
# is used.
#
# Release APKs come in one variant per Ruby (PSDK-android-ruby<x.y>-release.apk):
# the caller lists the ones that fit, preferred first.

require 'digest'
require 'fileutils'
require 'json'
require 'net/http'
require 'uri'

module BaseApk
  class Error < StandardError; end
  class NotFound < StandardError; end

  REPOSITORY    = 'Scorbutics/PSDK-android'
  API_URL       = "https://api.github.com/repos/#{REPOSITORY}/releases"
  RELEASES_URL  = "https://github.com/#{REPOSITORY}/releases"
  CACHE_DIR     = File.join(Dir.home, '.psdk', 'android-apk')
  # The variant of games compiled by the Windows Pokemon Studio
  DEFAULT_RUBY  = '3.0'
  TIMEOUT       = 10
  MAX_REDIRECTS = 5

  module_function

  def apk_name(ruby)
    "PSDK-android-ruby#{ruby}-release.apk"
  end

  # The release APK for the first of `rubies` the release has, downloaded
  # unless already cached.
  # @param rubies [Array<String>] the variants that fit ('3.0'...), preferred first
  # @param tag [String, nil] a release tag (v1.6.0...), nil for the latest release
  # @return [Array(String, String)] the APK path and its release tag
  def fetch(rubies, tag: nil, cache_dir: CACHE_DIR, api_url: API_URL)
    release = begin
      JSON.parse(http_get("#{api_url}/#{tag ? "tags/#{tag}" : 'latest'}"))
    rescue NotFound
      raise Error, tag ? "PSDK-android has no release #{tag} (#{RELEASES_URL})" : "PSDK-android has no release yet (#{RELEASES_URL})"
    rescue StandardError => e
      return cached(rubies, tag, cache_dir, "#{RELEASES_URL} can't be reached (#{e.message})")
    end

    tag_name = release['tag_name']
    assets = release['assets'].to_h { |asset| [asset['name'], asset] }
    ruby = rubies.find { |candidate| assets.key?(apk_name(candidate)) }
    unless ruby
      released = assets.keys.filter_map { |name| name[/\APSDK-android-ruby(.+)-release\.apk\z/, 1] }
      raise Error, "PSDK-android #{tag_name} has no APK for Ruby #{rubies.join(' or ')} " \
                   "(#{released.empty? ? 'none' : "#{released.join(', ')} only"}): pass --apk <base APK>"
    end

    asset = assets[apk_name(ruby)]
    path = File.join(cache_dir, tag_name, asset['name'])
    download(asset, path) unless File.file?(path) && File.size(path) == asset['size']
    remove_other_releases(cache_dir, tag_name) unless tag
    [path, tag_name]
  end

  # Without GitHub: the newest release cached (`tag` only, when given) with
  # one of `rubies`.
  def cached(rubies, tag, cache_dir, problem)
    tags = tag ? [tag] : Dir.glob('v*', base: cache_dir).sort_by { |candidate| version(candidate) }.reverse
    tags.product(rubies).each do |candidate, ruby|
      path = File.join(cache_dir, candidate, apk_name(ruby))
      next unless File.file?(path)

      warn "  #{problem}: using the downloaded base APK of #{candidate}"
      return [path, candidate]
    end
    raise Error, "No base APK downloaded#{" for #{tag}" if tag} yet, and #{problem}: " \
                 'check the connection, or pass --apk <base APK>'
  end

  # Comparable version of a tag (not Gem::Version: --disable=gems hides it)
  def version(tag)
    tag.delete_prefix('v').split('.').map(&:to_i)
  end

  # Downloads `asset` to `path` through a .part file, checked against the
  # size and digest GitHub gives.
  def download(asset, path)
    puts "  Downloading the base APK #{asset['name']} (#{File.basename(File.dirname(path))}, " \
         "#{(asset['size'] / 1_048_576.0).round(1)} MiB)..."
    FileUtils.mkdir_p(File.dirname(path))
    part = "#{path}.part"
    File.open(part, 'wb') { |file| http_get(asset['browser_download_url'], file) }
    raise "#{File.size(part)} bytes instead of #{asset['size']}" unless File.size(part) == asset['size']

    algorithm, digest = asset['digest'].to_s.split(':', 2)
    raise "#{algorithm} digest mismatch" if algorithm == 'sha256' && Digest::SHA256.file(part).hexdigest != digest

    # Windows can't rename over a file
    FileUtils.rm_f(path)
    File.rename(part, path)
  rescue StandardError => e
    FileUtils.rm_f(part) if part
    raise Error, "Can't download #{asset['browser_download_url']}: #{e.message}"
  end

  def remove_other_releases(cache_dir, tag)
    Dir.glob('v*', base: cache_dir).each { |other| FileUtils.rm_rf(File.join(cache_dir, other)) unless other == tag }
  end

  # GETs `url`, following redirects (GitHub serves assets from another
  # host): into `output` when given, else returns the body.
  def http_get(url, output = nil, redirects = MAX_REDIRECTS)
    uri = URI(url)
    Net::HTTP.start(uri.host, uri.port, use_ssl: uri.scheme == 'https',
                                        open_timeout: TIMEOUT, read_timeout: TIMEOUT * 6) do |http|
      request = Net::HTTP::Get.new(uri)
      request['User-Agent'] = 'psdk-android-exporter'
      http.request(request) do |response|
        case response
        when Net::HTTPRedirection
          raise 'Too many redirects' if redirects.zero?

          return http_get(URI.join(url, response['location']).to_s, output, redirects - 1)
        when Net::HTTPSuccess
          return response.body unless output

          response.read_body { |chunk| output.write(chunk) }
          return
        when Net::HTTPNotFound then raise NotFound, "HTTP 404 for #{url}"
        else raise "HTTP #{response.code} for #{url}"
        end
      end
    end
  end
end
