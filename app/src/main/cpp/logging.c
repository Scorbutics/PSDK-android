#ifndef NDEBUG
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <android/log.h>
#include <malloc.h>

#include "logging.h"

#define MAX_LOGFILE_SIZE 100 * 1024

int g_logging_thread_continue = 1;
pthread_t g_logging_thread = 0;
static int pfd[2];
static char *log_tag = NULL;
static FILE* fullLog = NULL;

static int ResizeGlobalLogBufferIfNeeded(char** globalBuffer, size_t* globalBufferCapacity, size_t newSize) {
	if (*globalBufferCapacity <= newSize) {
		*globalBufferCapacity = newSize * 1.5;
		char* newGlobalBuffer = realloc(*globalBuffer, *globalBufferCapacity * sizeof(*globalBuffer));
		if (*globalBuffer == NULL && *globalBufferCapacity != 0) {
			free(*globalBuffer);
			*globalBufferCapacity = 0;
			return 1;
		}
		*globalBuffer = newGlobalBuffer;
	}
	return 0;
}

static int AppendLog(const char* buf, char** globalBuffer, size_t* globalBufferCapacity, size_t* globalBufferSize, size_t stringSizeToAppend) {
	if (ResizeGlobalLogBufferIfNeeded(globalBuffer, globalBufferCapacity, *globalBufferSize + stringSizeToAppend + 1) != 0) {
		__android_log_write(ANDROID_LOG_ERROR, log_tag == NULL ? "UNKNOWN" : log_tag, "Internal memory error, aborting thread");
		return 1;
	}

	memcpy(*globalBuffer + *globalBufferSize, buf, stringSizeToAppend);
	*globalBufferSize += stringSizeToAppend;
	return 0;
}

static void WriteFullLogLine(const char* line) {
	__android_log_write(ANDROID_LOG_DEBUG, log_tag == NULL ? "UNKNOWN" : log_tag, line);
	if (fullLog != NULL) {
		if (ftell(fullLog) > MAX_LOGFILE_SIZE) {
			rewind (fullLog);
		}
		fprintf(fullLog, "%s\n", line);
	}
}

static void* loggingFunction(void* unused) {
	(void) unused;
	ssize_t readSize;
	char buf[64];
	char* globalBuffer = (char*) malloc(sizeof(buf));
	if (globalBuffer == NULL) {
		__android_log_write(ANDROID_LOG_ERROR, log_tag == NULL ? "UNKNOWN" : log_tag,
		                    "Internal memory error, aborting thread");
		return NULL;
	}
	size_t globalBufferSize = 0;
	size_t globalBufferCapacity = sizeof(buf) / sizeof(*buf);

	while (g_logging_thread_continue && (readSize = read(pfd[0], buf, sizeof buf - 1)) > 0) {
		size_t startBuf = 0;
		for (ssize_t index = 0; index < readSize; index++) {
			if (buf[index] == '\n') {
				/* When a line break, we append everything to the globalBuffer and then log it */
				AppendLog(buf + startBuf, &globalBuffer, &globalBufferCapacity, &globalBufferSize, index - startBuf);
				globalBuffer[globalBufferSize] = '\0';
				WriteFullLogLine(globalBuffer);
				globalBufferSize = 0;
				startBuf = index;
			}
		}

		/* Append the remaining and do nothing else */
		if (readSize - startBuf > 0) {
			AppendLog(buf + startBuf, &globalBuffer, &globalBufferCapacity, &globalBufferSize, readSize - startBuf);
		}
	}

	if (globalBufferSize > 0) {
		globalBuffer[globalBufferSize] = '\0';
		WriteFullLogLine(globalBuffer);
		globalBufferSize = 0;
	}
	WriteFullLogLine("----------------------------");
	__android_log_write(ANDROID_LOG_DEBUG, log_tag == NULL ? "UNKNOWN" : log_tag, "Logging thread ended");

	free(globalBuffer);
	free(log_tag);
	fclose(fullLog);
	return NULL;
}

int LoggingThreadRun(const char* appname, const char* extraLogFile) {
	setvbuf(stdout, 0, _IONBF, 0); // make stdout line-buffered
	setvbuf(stderr, 0, _IONBF, 0); // make stderr unbuffered

	log_tag = strdup(appname);
	fullLog = fopen(extraLogFile, "a+");

	pipe(pfd);
	dup2(pfd[1], STDERR_FILENO);
	dup2(pfd[1], STDOUT_FILENO);

	if (pthread_create(&g_logging_thread, 0, loggingFunction, 0) == -1) {
		__android_log_write(ANDROID_LOG_WARN, log_tag == NULL ? "UNKNOWN" : log_tag,
											"Cannot spawn logging thread : logging from stdout / stderr won't show in logcat");
		return -1;
	}
	__android_log_write(ANDROID_LOG_DEBUG, log_tag == NULL ? "UNKNOWN" : log_tag, "Logging thread started");
	return 0;
}
#endif