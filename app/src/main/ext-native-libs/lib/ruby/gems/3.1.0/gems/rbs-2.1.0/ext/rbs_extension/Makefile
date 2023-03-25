
SHELL = /bin/sh

# V=0 quiet, V=1 verbose.  other values don't work.
V = 0
V0 = $(V:0=)
Q1 = $(V:1=)
Q = $(Q1:0=@)
ECHO1 = $(V:1=@ :)
ECHO = $(ECHO1:0=@ echo)
NULLCMD = :

#### Start of system configuration section. ####

srcdir = .
topdir = /opt/current/build/rubyfull/build_dir/rubyfull-3.1.1/ruby-build/ruby/build_dir/ruby-3.1.1/./include
hdrdir = $(topdir)
arch_hdrdir = /opt/current/build/rubyfull/build_dir/rubyfull-3.1.1/ruby-build/ruby/build_dir/ruby-3.1.1/.ext/include/aarch64-linux-android-android
PATH_SEPARATOR = :
VPATH = $(srcdir):$(arch_hdrdir)/ruby:$(hdrdir)/ruby
prefix = $(DESTDIR)/usr/local
rubysitearchprefix = $(rubylibprefix)/$(sitearch)
rubyarchprefix = $(rubylibprefix)/$(arch)
rubylibprefix = $(libdir)/$(RUBY_BASE_NAME)
exec_prefix = $(prefix)
vendorarchhdrdir = $(vendorhdrdir)/$(sitearch)
sitearchhdrdir = $(sitehdrdir)/$(sitearch)
rubyarchhdrdir = $(extout)/include/$(arch)
vendorhdrdir = $(rubyhdrdir)/vendor_ruby
sitehdrdir = $(rubyhdrdir)/site_ruby
rubyhdrdir = $(top_srcdir)/include
vendorarchdir = $(vendorlibdir)/$(sitearch)
vendorlibdir = $(vendordir)/$(ruby_version)
vendordir = $(rubylibprefix)/vendor_ruby
sitearchdir = $(DESTDIR)./.gem.20230110-68684-auq7z4
sitelibdir = $(DESTDIR)./.gem.20230110-68684-auq7z4
sitedir = $(rubylibprefix)/site_ruby
rubyarchdir = $(rubylibdir)/$(arch)
rubylibdir = $(rubylibprefix)/$(ruby_version)
sitearchincludedir = $(includedir)/$(sitearch)
archincludedir = $(includedir)/$(arch)
sitearchlibdir = $(libdir)/$(sitearch)
archlibdir = $(libdir)/$(arch)
ridir = $(datarootdir)/$(RI_BASE_NAME)
mandir = $(datarootdir)/man
localedir = $(datarootdir)/locale
libdir = $(DESTDIR)/opt/current/build/rubyfull/build_dir/rubyfull-3.1.1/ruby-build/ruby/build_dir/ruby-3.1.1
psdir = $(docdir)
pdfdir = $(docdir)
dvidir = $(docdir)
htmldir = $(docdir)
infodir = $(datarootdir)/info
docdir = $(datarootdir)/doc/$(PACKAGE)
oldincludedir = $(DESTDIR)/usr/include
includedir = $(prefix)/include
runstatedir = $(localstatedir)/run
localstatedir = $(prefix)/var
sharedstatedir = $(prefix)/com
sysconfdir = $(prefix)/etc
datadir = $(datarootdir)
datarootdir = $(prefix)/share
libexecdir = $(exec_prefix)/libexec
sbindir = $(exec_prefix)/sbin
bindir = $(exec_prefix)/bin
archdir = $(rubyarchdir)
buildlibdir = $(DESTDIR)/opt/current/build/rubyfull/build_dir/rubyfull-3.1.1/ruby-build/ruby/build_dir/ruby-3.1.1


CC_WRAPPER = 
CC = /opt/android-ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/clang -target aarch64-linux-android26
CXX = /opt/android-ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/clang++ -target aarch64-linux-android26
LIBRUBY = $(LIBRUBY_SO)
LIBRUBY_A = lib$(RUBY_SO_NAME)-static.a
LIBRUBYARG_SHARED = -Wl,-rpath,$(libdir) -l$(RUBY_SO_NAME)
LIBRUBYARG_STATIC = -Wl,-rpath,$(libdir) -l$(RUBY_SO_NAME)-static $(MAINLIBS)
empty =
OUTFLAG = -o $(empty)
COUTFLAG = -o $(empty)
CSRCFLAG = $(empty)

RUBY_EXTCONF_H = 
cflags   = -fdeclspec $(optflags) $(debugflags) $(warnflags)
cxxflags = 
optflags = -O3 -fno-fast-math
debugflags = -ggdb3
warnflags = -Wall -Wextra -Wdeprecated-declarations -Wdivision-by-zero -Wimplicit-function-declaration -Wimplicit-int -Wmisleading-indentation -Wpointer-arith -Wshorten-64-to-32 -Wwrite-strings -Wold-style-definition -Wmissing-noreturn -Wno-cast-function-type -Wno-constant-logical-operand -Wno-long-long -Wno-missing-field-initializers -Wno-overlength-strings -Wno-parentheses-equality -Wno-self-assign -Wno-tautological-compare -Wno-unused-parameter -Wno-unused-value -Wunused-variable -Wextra-tokens -Wundef
cppflags = 
CCDLFLAGS = -fPIC
CFLAGS   = $(CCDLFLAGS) -I/opt/current/build/rubyfull/build_dir/rubyfull-3.1.1/target/usr/include -I/opt/current/build/rubyfull/build_dir/rubyfull-3.1.1/target/usr/local/include -O3 -DNDEBUG -U__ANDROID_API__ -D__ANDROID_API__=26 $(cflags) -fPIC $(ARCH_FLAG)
INCFLAGS = -I. -I$(arch_hdrdir) -I$(hdrdir)/ruby/backward -I$(hdrdir) -I$(srcdir)
DEFS     = 
CPPFLAGS =    -I/opt/current/build/rubyfull/build_dir/rubyfull-3.1.1/target/usr/include -I/opt/current/build/rubyfull/build_dir/rubyfull-3.1.1/target/usr/local/include -O3 -DNDEBUG -U__ANDROID_API__ -D__ANDROID_API__=26 $(DEFS) $(cppflags)
CXXFLAGS = $(CCDLFLAGS) -fdeclspec $(ARCH_FLAG)
ldflags  = -L.  -lz -lm -L/opt/current/build/rubyfull/build_dir/rubyfull-3.1.1/target/usr/lib -L/opt/current/build/rubyfull/build_dir/rubyfull-3.1.1/target/usr/local/lib -fstack-protector-strong -rdynamic -Wl,-export-dynamic -Wl,--no-as-needed
dldflags =  -lz -lm -L/opt/current/build/rubyfull/build_dir/rubyfull-3.1.1/target/usr/lib -L/opt/current/build/rubyfull/build_dir/rubyfull-3.1.1/target/usr/local/lib -Wl,--compress-debug-sections=zlib 
ARCH_FLAG = 
DLDFLAGS = $(ldflags) $(dldflags) $(ARCH_FLAG)
LDSHARED = $(CC) -shared
LDSHAREDXX = $(CXX) -shared
AR = /opt/android-ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ar
EXEEXT = 

RUBY_INSTALL_NAME = $(RUBY_BASE_NAME)
RUBY_SO_NAME = ruby
RUBYW_INSTALL_NAME = 
RUBY_VERSION_NAME = $(RUBY_BASE_NAME)-$(ruby_version)
RUBYW_BASE_NAME = rubyw
RUBY_BASE_NAME = ruby

arch = aarch64-linux-android-android
sitearch = $(arch)
ruby_version = 3.1.0
ruby = /usr/bin/ruby --disable=gems
RUBY = $(ruby)
ruby_headers = $(hdrdir)/ruby.h $(hdrdir)/ruby/backward.h $(hdrdir)/ruby/ruby.h $(hdrdir)/ruby/defines.h $(hdrdir)/ruby/missing.h $(hdrdir)/ruby/intern.h $(hdrdir)/ruby/st.h $(hdrdir)/ruby/subst.h $(arch_hdrdir)/ruby/config.h

RM = rm -f
RM_RF = rm -fr
RMDIRS = rmdir --ignore-fail-on-non-empty -p
MAKEDIRS = mkdir -p
INSTALL = /usr/bin/install -c
INSTALL_PROG = $(INSTALL) -m 0755
INSTALL_DATA = $(INSTALL) -m 644
COPY = cp
TOUCH = exit >

#### End of system configuration section. ####

preload = 
libpath = . $(buildlibdir)
LIBPATH =  -L. -L$(buildlibdir) -Wl,-rpath,$(buildlibdir)
DEFFILE = 

CLEANFILES = mkmf.log
DISTCLEANFILES = 
DISTCLEANDIRS = 

extout = $(topdir)/.ext
extout_prefix = $(extout)$(target_prefix)/
target_prefix = 
LOCAL_LIBS = 
LIBS = $(LIBRUBYARG_SHARED)  -lm  -lc
ORIG_SRCS = constants.c lexer.c lexstate.c location.c main.c parser.c parserstate.c ruby_objs.c unescape.c
SRCS = $(ORIG_SRCS) 
OBJS = constants.o lexer.o lexstate.o location.o main.o parser.o parserstate.o ruby_objs.o unescape.o
HDRS = $(srcdir)/constants.h $(srcdir)/lexer.h $(srcdir)/location.h $(srcdir)/parser.h $(srcdir)/parserstate.h $(srcdir)/rbs_extension.h $(srcdir)/ruby_objs.h
LOCAL_HDRS = 
TARGET = rbs_extension
TARGET_NAME = rbs_extension
TARGET_ENTRY = Init_$(TARGET_NAME)
DLLIB = $(TARGET).so
EXTSTATIC = 
STATIC_LIB = $(TARGET).a

TIMESTAMP_DIR = .
BINDIR        = $(bindir)
RUBYCOMMONDIR = $(sitedir)$(target_prefix)
RUBYLIBDIR    = $(sitelibdir)$(target_prefix)
RUBYARCHDIR   = $(sitearchdir)$(target_prefix)
HDRDIR        = $(sitehdrdir)$(target_prefix)
ARCHHDRDIR    = $(sitearchhdrdir)$(target_prefix)
TARGET_SO_DIR = $(RUBYARCHDIR)/
TARGET_SO     = $(TARGET_SO_DIR)$(DLLIB)
CLEANLIBS     = $(TARGET_SO) false
CLEANOBJS     = *.o  *.bak

all:    install
static: $(STATIC_LIB) install-rb
.PHONY: all install static install-so install-rb
.PHONY: clean clean-so clean-static clean-rb

clean-static::
clean-rb-default::
clean-rb::
clean-so::
clean: clean-so clean-static clean-rb-default clean-rb
		-$(Q)$(RM_RF) $(CLEANLIBS) $(CLEANOBJS) $(CLEANFILES) .*.time

distclean-rb-default::
distclean-rb::
distclean-so::
distclean-static::
distclean: clean distclean-so distclean-static distclean-rb-default distclean-rb
		-$(Q)$(RM) Makefile $(RUBY_EXTCONF_H) conftest.* mkmf.log
		-$(Q)$(RM) core ruby$(EXEEXT) *~ $(DISTCLEANFILES)
		-$(Q)$(RMDIRS) $(DISTCLEANDIRS) 2> /dev/null || true

realclean: distclean
install: install-so install-rb

install-so: $(TARGET_SO)
clean-so::
	-$(Q)$(RM) $(TARGET_SO) $(TIMESTAMP_DIR)/.sitearchdir.time
	-$(Q)$(RMDIRS) $(TARGET_SO_DIR) 2> /dev/null || true
clean-static::
	-$(Q)$(RM) $(STATIC_LIB)
install-rb: pre-install-rb do-install-rb install-rb-default
install-rb-default: pre-install-rb-default do-install-rb-default
pre-install-rb: Makefile
pre-install-rb-default: Makefile
do-install-rb:
do-install-rb-default:
pre-install-rb-default:
	@$(NULLCMD)
$(TIMESTAMP_DIR)/.sitearchdir.time:
	$(Q) $(MAKEDIRS) $(@D) $(TARGET_SO_DIR)
	$(Q) $(TOUCH) $@

site-install: site-install-so site-install-rb
site-install-so: install-so
site-install-rb: install-rb

.SUFFIXES: .c .m .cc .mm .cxx .cpp .o .S

.cc.o:
	$(ECHO) compiling $(<)
	$(Q) $(CXX) $(INCFLAGS) $(CPPFLAGS) $(CXXFLAGS) $(COUTFLAG)$@ -c $(CSRCFLAG)$<

.cc.S:
	$(ECHO) translating $(<)
	$(Q) $(CXX) $(INCFLAGS) $(CPPFLAGS) $(CXXFLAGS) $(COUTFLAG)$@ -S $(CSRCFLAG)$<

.mm.o:
	$(ECHO) compiling $(<)
	$(Q) $(CXX) $(INCFLAGS) $(CPPFLAGS) $(CXXFLAGS) $(COUTFLAG)$@ -c $(CSRCFLAG)$<

.mm.S:
	$(ECHO) translating $(<)
	$(Q) $(CXX) $(INCFLAGS) $(CPPFLAGS) $(CXXFLAGS) $(COUTFLAG)$@ -S $(CSRCFLAG)$<

.cxx.o:
	$(ECHO) compiling $(<)
	$(Q) $(CXX) $(INCFLAGS) $(CPPFLAGS) $(CXXFLAGS) $(COUTFLAG)$@ -c $(CSRCFLAG)$<

.cxx.S:
	$(ECHO) translating $(<)
	$(Q) $(CXX) $(INCFLAGS) $(CPPFLAGS) $(CXXFLAGS) $(COUTFLAG)$@ -S $(CSRCFLAG)$<

.cpp.o:
	$(ECHO) compiling $(<)
	$(Q) $(CXX) $(INCFLAGS) $(CPPFLAGS) $(CXXFLAGS) $(COUTFLAG)$@ -c $(CSRCFLAG)$<

.cpp.S:
	$(ECHO) translating $(<)
	$(Q) $(CXX) $(INCFLAGS) $(CPPFLAGS) $(CXXFLAGS) $(COUTFLAG)$@ -S $(CSRCFLAG)$<

.c.o:
	$(ECHO) compiling $(<)
	$(Q) $(CC) $(INCFLAGS) $(CPPFLAGS) $(CFLAGS) $(COUTFLAG)$@ -c $(CSRCFLAG)$<

.c.S:
	$(ECHO) translating $(<)
	$(Q) $(CC) $(INCFLAGS) $(CPPFLAGS) $(CFLAGS) $(COUTFLAG)$@ -S $(CSRCFLAG)$<

.m.o:
	$(ECHO) compiling $(<)
	$(Q) $(CC) $(INCFLAGS) $(CPPFLAGS) $(CFLAGS) $(COUTFLAG)$@ -c $(CSRCFLAG)$<

.m.S:
	$(ECHO) translating $(<)
	$(Q) $(CC) $(INCFLAGS) $(CPPFLAGS) $(CFLAGS) $(COUTFLAG)$@ -S $(CSRCFLAG)$<

$(TARGET_SO): $(OBJS) Makefile $(TIMESTAMP_DIR)/.sitearchdir.time
	$(ECHO) linking shared-object $(DLLIB)
	-$(Q)$(RM) $(@)
	$(Q) $(LDSHARED) -o $@ $(OBJS) $(LIBPATH) $(DLDFLAGS) $(LOCAL_LIBS) $(LIBS)

$(STATIC_LIB): $(OBJS)
	-$(Q)$(RM) $(@)
	$(ECHO) linking static-library $(@)
	$(Q) $(AR) rcD $@ $(OBJS)
	-$(Q)/opt/android-ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ranlib $(@) 2> /dev/null || true

$(OBJS): $(HDRS) $(ruby_headers)
