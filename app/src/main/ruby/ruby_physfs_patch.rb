require 'LiteRGSS'

Project_path = Dir.pwd
DATA_ARCHIVE = LiteRGSS::AssetsArchive.new "./data.zip";
CODE_ARCHIVE = LiteRGSS::AssetsArchive.new "./code.zip";

LiteRGSS::AssetWriter::write_dir = Project_path

class ::File
  def self.path_in_assets(filename)
    filename = File.expand_path(filename)
    rel_path = filename.split(Project_path)[1]
    rel_path = filename if rel_path.nil?
    return LiteRGSS::AssetFile::exist?(rel_path) ? rel_path : nil
  end

  Old_file_exist_ = method(:exist?)
  def File.exist?(filename)
    return true if Old_file_exist_.call(filename)
    asset_file_path = path_in_assets(filename)
    return true if !asset_file_path.nil?
    return false
  end

  Old_file_file_ = method(:file?)
  def File.file?(filename)
    return true if Old_file_file_.call(filename)
    asset_file_path = path_in_assets(filename)
    return true if !asset_file_path.nil?
    return false
  end

  Old_file_read_ = method(:read)
  def File.read(filename)
    begin
      return Old_file_read_.call(filename)
    rescue Exception
      begin
        asset_file_path = path_in_assets(filename)
        file = LiteRGSS::AssetFile.new(asset_file_path.nil? ? filename : asset_file_path, "r")
        content = file.read().force_encoding(Encoding::UTF_8)
        file.close
        return content 
      ensure
        file.close if file
      end
    end
  end

  Old_file_binread_ = method(:binread)
  def File.binread(filename)
    begin
      return Old_file_binread_.call(filename)
    rescue Exception
      begin
        asset_file_path = path_in_assets(filename)
        file = LiteRGSS::AssetFile.new(asset_file_path.nil? ? filename : asset_file_path, "rb")
        content = file.read()
        file.close
        return content 
      ensure
        file.close if file
      end
    end
  end

  def File.mtime(filename)
    return 0
  end

  def File.readlines(filename)
    content = File.read(filename)
    return content.split("\n")
  end

  Old_file_open_ = method(:open)
  def File.open(filename, mode, **file_opts)
    if Old_file_exist_.call(filename)
      if !block_given?
        return Old_file_open_.call(filename, mode, **file_opts)
      else
        return Old_file_open_.call(filename, mode, **file_opts) do |file|
          begin
            yield(file)
          ensure
            file.close
          end
          return nil
        end
      end
    end
    asset_file_path = path_in_assets(filename)

    begin
      file = LiteRGSS::AssetFile.new(asset_file_path, mode.nil? ? "r" : mode)
      if block_given?
        begin
          yield(file)
        ensure
          file.close
        end
        return nil
      end
      return file
    rescue Exception
      puts $!.message
    end
  end
end

def global_require(moduleName)
  begin
    data = File.read(moduleName)
  rescue
    raise LoadError.new $!
  end
  return eval(data)
end

module Kernel
  alias :oldRequire :require

  def require(moduleName)
    begin
      oldRequire(moduleName)
    rescue LoadError 
      return zip_require(moduleName)
    end
  end

  alias :oldRequireRelative :require_relative

  def require_relative(moduleName)
    caller_script_path = caller_locations(1)[0].absolute_path()
    if caller_script_path.nil?
      relative_path = moduleName
    else
      relative_path = File.expand_path(moduleName, File.dirname(caller_script_path))
    end

    require relative_path
  end

  def zip_require(moduleName)
    return true if already_loaded?(moduleName)
    moduleName = ensure_rb_extension(moduleName)
    return global_require(moduleName)
  end

  def already_loaded?(moduleName)
    moduleRE = Regexp.new("^"+moduleName+"(\.rb|\.so|\.dll|\.o)?$")
    $".detect { |e| e =~ moduleRE } != nil
  end

  def ensure_rb_extension(aString)
    # No support for upper directories in assets require
    aString.sub(/(\.rb)?$/i, ".rb").sub(/^(\.\.\/)+/i, "pokemonsdk/")
  end
end
