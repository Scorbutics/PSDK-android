/*
   /!\ THIS FILE IS AN EDITED VERSION OF THE ONE ORIGINALLY "miniunz.c" PRESENT IN MINIZIP
   /!\ IT HAS BEEN REWORKED A BIT AND SOME PARTS WERE CUT (no need for password + always overwrite + extract with path)

   miniunz.c
   Version 1.1, February 14h, 2010
   sample part of the MiniZip project - ( http://www.winimage.com/zLibDll/minizip.html )

         Copyright (C) 1998-2010 Gilles Vollant (minizip) ( http://www.winimage.com/zLibDll/minizip.html )

         Modifications of Unzip for Zip64
         Copyright (C) 2007-2008 Even Rouault

         Modifications for Zip64 support on both zip and unzip
         Copyright (C) 2009-2010 Mathias Svensson ( http://result42.com )
*/

#if (!defined(_WIN32)) && (!defined(WIN32)) && (!defined(__APPLE__))
#ifndef __USE_FILE_OFFSET64
#define __USE_FILE_OFFSET64
#endif
#ifndef __USE_LARGEFILE64
#define __USE_LARGEFILE64
#endif
#ifndef _LARGEFILE64_SOURCE
#define _LARGEFILE64_SOURCE
#endif
#ifndef _FILE_OFFSET_BIT
#define _FILE_OFFSET_BIT 64
#endif
#endif

#ifdef __APPLE__
// In darwin and perhaps other BSD variants off_t is a 64 bit value, hence no need for specific 64 bit functions
#define FOPEN_FUNC(filename, mode) fopen(filename, mode)
#define FTELLO_FUNC(stream) ftello(stream)
#define FSEEKO_FUNC(stream, offset, origin) fseeko(stream, offset, origin)
#else
#define FOPEN_FUNC(filename, mode) fopen64(filename, mode)
#define FTELLO_FUNC(stream) ftello64(stream)
#define FSEEKO_FUNC(stream, offset, origin) fseeko64(stream, offset, origin)
#endif

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <errno.h>
#include <fcntl.h>

#ifdef _WIN32
# include <direct.h>
# include <io.h>
#else
# include <unistd.h>
# include <utime.h>
# include <sys/stat.h>
#endif

#include "unzip.h"

#define WRITEBUFFERSIZE (8192)
#define MAXFILENAME (256)

/* portable_mkdir is not 100 % portable
   As I don't know well Unix, I wait feedback for the unix portion */
static int portable_mkdir(const char* dirname) {
	int ret = 0;
#ifdef _WIN32
	ret = _mkdir(dirname);
#elif unix
	ret = mkdir (dirname,0775);
#elif __APPLE__
	ret = mkdir (dirname, 0775);
#endif
	return ret;
}

static int makedir (const char * newdir) {
	const int len = (int) strlen(newdir);
	if (len <= 0) {
		return 0;
	}

	char* buffer = (char*) malloc(len+1);
	if (buffer == NULL) {
		fprintf(stderr, "error allocating memory\n");
		return UNZ_INTERNALERROR;
	}
	strcpy(buffer, newdir);

	if (buffer[len-1] == '/') {
		buffer[len-1] = '\0';
	}
	if (portable_mkdir(buffer) == 0) {
		free(buffer);
		return 1;
	}

	char* p = buffer + 1;
	while (1) {
		char hold;

		while(*p && *p != '\\' && *p != '/') {
			p++;
		}
		hold = *p;
		*p = 0;
		if ((portable_mkdir(buffer) == -1) && (errno == ENOENT)) {
			fprintf(stderr, "couldn't create directory %s\n",buffer);
			free(buffer);
			return 0;
		}
		if (hold == 0) {
			break;
		}
		*p++ = hold;
	}
	free(buffer);
	return 1;
}

static int do_extract_currentfile(unzFile uf) {
	unz_file_info64 file_info;
	char filename_inzip[256];
	const int err_get_info = unzGetCurrentFileInfo64(uf, &file_info, filename_inzip, sizeof(filename_inzip),NULL, 0,NULL,0);
	if (err_get_info != UNZ_OK) {
		fprintf(stderr,"error %d with zipfile in unzGetCurrentFileInfo\n", err_get_info);
		return err_get_info;
	}

	const uInt size_buf = WRITEBUFFERSIZE;
	void* buf = (void*) malloc(size_buf);
	if (buf == NULL) {
		fprintf(stderr,"Error allocating memory\n");
		return UNZ_INTERNALERROR;
	}

	char* filename_withoutpath = filename_inzip;
	char* p = filename_inzip;
	while ((*p) != '\0') {
		if (((*p) == '/') || ((*p) == '\\')) {
			filename_withoutpath = p + 1;
		}
		p++;
	}

	if (*filename_withoutpath == '\0') {
		portable_mkdir(filename_inzip);
		free(buf);
		return UNZ_OK;
	}

	const int errOpenCurrentFilePassword = unzOpenCurrentFilePassword(uf, NULL);
	if (errOpenCurrentFilePassword != UNZ_OK) {
		fprintf(stderr,"error %d with zipfile in unzOpenCurrentFilePassword\n", errOpenCurrentFilePassword);
	}

	FILE * fout = NULL;
	if (errOpenCurrentFilePassword == UNZ_OK) {
		const char* write_filename = filename_inzip;
		fout = FOPEN_FUNC(write_filename,"wb");
		/* some zipfile don't contain directory alone before file */
		if ((fout == NULL) && (filename_withoutpath != (char*)filename_inzip)) {
			const char c = *(filename_withoutpath - 1);
			*(filename_withoutpath-1) = '\0';
			makedir(write_filename);
			*(filename_withoutpath-1) = c;
			fout = FOPEN_FUNC(write_filename,"wb");
		}

		if (fout == NULL) {
			fprintf(stderr,"error opening %s\n", write_filename);
		}
	}

	int errReadCurrentFile = fout == NULL ? UNZ_ERRNO : UNZ_OK;
	if (fout != NULL) {
		do {
			errReadCurrentFile = unzReadCurrentFile(uf,buf,size_buf);
			if (errReadCurrentFile < 0) {
				fprintf(stderr, "error %d with zipfile in unzReadCurrentFile\n", errReadCurrentFile);
				break;
			}
			if (errReadCurrentFile > 0)
				if (fwrite(buf, errReadCurrentFile,1, fout)!=1) {
					fprintf(stderr, "error in writing extracted file\n");
					errReadCurrentFile = UNZ_ERRNO;
					break;
				}
		}
		while (errReadCurrentFile > 0);
		if (fout)
			fclose(fout);
	}

	if (errReadCurrentFile == UNZ_OK) {
		const int errCloseCurrentFile = unzCloseCurrentFile (uf);
		if (errCloseCurrentFile != UNZ_OK) {
			fprintf(stderr, "error %d with zipfile in unzCloseCurrentFile\n", errCloseCurrentFile);
		}
	} else {
		unzCloseCurrentFile(uf); /* don't lose the error */
	}

	free(buf);
	return errReadCurrentFile;
}

static int do_extract(unzFile uf) {
	unz_global_info64 gi;
	const int err = unzGetGlobalInfo64(uf, &gi);
	if (err != UNZ_OK) {
		fprintf(stderr, "error %d with zipfile in unzGetGlobalInfo64 \n", err);
	}

	for (uLong i = 0; i < gi.number_entry; i++) {
		if (do_extract_currentfile(uf) != UNZ_OK) {
			break;
		}

		if ((i+1) < gi.number_entry) {
			const int err_next = unzGoToNextFile(uf);
			if (err_next != UNZ_OK) {
				fprintf(stderr,"error %d with zipfile in unzGoToNextFile\n", err_next);
				break;
			}
		}
	}

	return 0;
}

int lite_unzip(const char * zip_filename, const char *dirname) {
	unzFile uf = NULL;
	if (zip_filename != NULL) {
		char filename_try[MAXFILENAME + 16] = "";
		strncpy(filename_try, zip_filename,MAXFILENAME - 1);
		filename_try[MAXFILENAME] = '\0';

		uf = unzOpen64(zip_filename);
		if (uf == NULL) {
			strncat(filename_try,".zip", sizeof(filename_try));
			uf = unzOpen64(filename_try);
		}
	}

	if (uf == NULL) {
		fprintf(stderr,"Cannot open '%s' or '%s.zip'\n", zip_filename, zip_filename);
		return 1;
	}

	if (chdir(dirname)) {
		fprintf(stderr, "Cannot change current directory into '%s'\n", dirname);
		unzClose(uf);
		return 2;
	}

	const int ret_value = do_extract(uf);
	unzClose(uf);

	return ret_value;
}
