# The small APK's game: hosted on the web next to a manifest, downloaded by
# the app at its first launch, instead of bundled in the APK (export_android.rb
# --remote). A 640 MB APK downloaded by a phone's browser often arrives
# corrupted; the app's download resumes, checks the game's SHA-256, and
# updates the game later without a new APK.
#
# Two documents, read by the app (com.psdk.remote):
#   assets/game.remote.json, in the APK: where the manifest is.
#     {"format": 1, "manifest_url": "https://.../android-game.json"}
#   android-game.json, hosted, rewritten by each export of the game:
#     {"format": 1, "title": ..., "version": ...,
#      "archive": {"url": "game.epsa", "size": ..., "sha256": ...},
#      "ruby": "3.0", "backend": "raylib", "compiled": true,
#      "cert_sha256": ..., "min_app_version": "1.13.6"}
#   archive.url is relative to the manifest's URL unless absolute: next to
#   it by default (a GitHub release's .../releases/latest/download/).
#   cert_sha256: the archive only opens on an APK signed with this cert (its
#   keys derive from it); min_app_version: the base APK's versionName, the
#   oldest app whose engine plays it.
require 'json'
require 'digest'
require 'uri'

module RemoteGame
  FORMAT = 1
  # In the APK
  ASSET = 'assets/game.remote.json'.freeze
  # Next to the archive, uploaded with it
  MANIFEST_NAME = 'android-game.json'.freeze

  Error = Class.new(StandardError)

  module_function

  # @raise [Error] unless an absolute http(s) URL
  def check_url!(url, what)
    uri = URI.parse(url)
    raise Error, "#{what} must be an absolute http(s) URL: #{url}" unless %w[http https].include?(uri.scheme) && uri.host
  rescue URI::InvalidURIError
    raise Error, "#{what} is not a URL: #{url}"
  end

  # The APK's assets/game.remote.json
  def apk_asset(manifest_url)
    JSON.pretty_generate('format' => FORMAT, 'manifest_url' => manifest_url) << "\n"
  end

  # android-game.json, for the archive at `archive_path`
  def manifest(title:, version:, archive_path:, archive_url:, ruby:, backend:, compiled:, cert_der:, min_app_version:)
    JSON.pretty_generate(
      'format' => FORMAT,
      'title' => title,
      'version' => version.to_s,
      'archive' => {
        'url' => archive_url,
        'size' => File.size(archive_path),
        'sha256' => Digest::SHA256.file(archive_path).hexdigest
      },
      'ruby' => ruby,
      'backend' => backend,
      'compiled' => compiled,
      'cert_sha256' => cert_der && Digest::SHA256.hexdigest(cert_der),
      'min_app_version' => min_app_version
    ) << "\n"
  end

  # The game's version: --game-version, else PSDK's gameVersion
  # (Data/configs/infos_config.json), else the export's date
  def game_version(option, project_dir = '.')
    return option if option

    infos = JSON.parse(File.read(File.join(project_dir, 'Data/configs/infos_config.json')))
    version = infos['gameVersion']
    version.nil? ? Time.now.utc.strftime('%Y%m%d%H%M') : version.to_s
  rescue SystemCallError, JSON::ParserError
    Time.now.utc.strftime('%Y%m%d%H%M')
  end
end
