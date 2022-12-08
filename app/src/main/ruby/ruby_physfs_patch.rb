require 'LiteRGSS'

Project_path = Dir.pwd
STDERR.puts Project_path
PROJECT_ARCHIVE = LiteRGSS::AssetsArchive.new Project_path + "/project.psa"
#DATA_ARCHIVE = LiteRGSS::AssetsArchive.new Project_path + "/data.zip"
#CODE_ARCHIVE = LiteRGSS::AssetsArchive.new Project_path + "/code.zip"

LiteRGSS::AssetWriter::write_dir = Project_path

class ::File
  def self.path_in_assets(filename)
    filename = File.expand_path(filename)
    rel_path = filename.split(Project_path)[1]
    rel_path = filename if rel_path.nil?
    return LiteRGSS::AssetFile::exist?((Dir.physfs_pwd.nil? ? "" : + Dir.physfs_pwd + "/") + rel_path) ? rel_path : nil
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

    def File.copy_stream(src, dst)
      content = File.read(src)
      Old_file_open_.call(dst, 'w') { |file| file.write(content) }
      return 0
    end
end

class ::Dir

  @@_Physfs_virtual_pwd = nil

  def Dir.physfs_pwd
    return @@_Physfs_virtual_pwd
  end

  Old_dir_chdir_ = method(:chdir)
  def Dir.chdir(path)
    if File.directory?(path)
        return Old_dir_chdir_.call(path)
    end
    old_path = @@_Physfs_virtual_pwd
    @@_Physfs_virtual_pwd = path
    if block_given?
        begin
            yield
        ensure
            @@_Physfs_virtual_pwd = old_path
        end
    end
  end

  Old_dir_square_ = method(:[])
  def Dir.[](search_pattern)

    directory_path = search_pattern.sub(/(\/[\*.a-zA-Z0-9]+)?(\/)?$/i, "")
    if directory_path == search_pattern
        search = search_pattern
        directory_path = ""
    else
        search = search_pattern.sub(directory_path, "").sub("/", "")
    end
    supp_files = []
    if File.directory?(directory_path)
      #STDERR.puts "REAL DIRECTORY #{directory_path} (#{search_pattern})"
      # By default, we add files from real FS to the zip FS
      supp_files = Old_dir_square_.call(search_pattern)
    end
    #STDERR.puts "SEARCH #{search} in #{directory_path} (#{search_pattern})"
    if directory_path.include?("**")
        dirs = directory_path.split("/**")
        directory_path = dirs[0]
        if dirs.length > 1
            raise "UNSUPPORTED"
        end
        search_append = ""
        if dirs.length == 2
            search_append = dirs[1]
        end
        STDERR.puts "RECURSIVE #{search} in #{directory_path} (#{search_pattern})"
        physfs_dir_path = @@_Physfs_virtual_pwd.nil? ? directory_path : (@@_Physfs_virtual_pwd + "/" + directory_path)
        all_dirs = LiteRGSS::AssetFile::enumerate(physfs_dir_path).map {|item| physfs_dir_path + "/" + item }
        final_files = []
        all_dirs.each do |dir|
            if LiteRGSS::AssetFile::is_directory? dir
                final_search = dir + "/" + search + search_append
                STDERR.puts "GO RECURSIVE SEARCH #{final_search} in #{directory_path} (#{search_pattern})"
                final_files << Dir[final_search]
            else
                STDERR.puts "Not a directory #{dir}"
                final_files << dir
            end
        end
        return final_files
    elsif search.start_with?("*")
        physfs_dir_path = @@_Physfs_virtual_pwd.nil? ? directory_path : (@@_Physfs_virtual_pwd + "/" + directory_path)
        all_files = LiteRGSS::AssetFile::enumerate(physfs_dir_path)
        search = search.sub("*", "")
        if search != ".*" && search != "/" && search != ".*/"
            final_files = all_files.select {|item| item.end_with? search }
        else
            final_files = all_files
            final_files = final_files.select { |item| !LiteRGSS::AssetFile::is_directory? (physfs_dir_path + "/" + item) } if (search == ".*" || search == ".*/")
        end
        STDERR.puts "SEARCH FILES #{search} in #{directory_path} (#{search_pattern})" if !final_files.empty?
        build_final_path = -> (item) {
            full_item_path = physfs_dir_path + "/" + item
            if LiteRGSS::AssetFile::is_directory? (full_item_path)
                return "#{full_item_path}/"
            end
            return full_item_path
        }
        final_files = final_files.map {|item| build_final_path.call(item) }
        return final_files + supp_files
    else
        STDERR.puts "UNKNOWN #{search} (#{search_pattern})"
        raise "UNKNOWN #{search} (#{search_pattern})"
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
