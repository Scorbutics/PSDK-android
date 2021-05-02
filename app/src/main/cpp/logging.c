#ifndef NDEBUG
#include <stdio.h>
#include <pthread.h>
#include <string.h>
#include <unistd.h>
#include <android/log.h>
#include <malloc.h>

static int pfd[2];
static pthread_t loggingThread;
static char *log_tag = NULL;

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

	while ((readSize = read(pfd[0], buf, sizeof buf - 1)) > 0) {
		size_t startBuf = 0;
		for (ssize_t index = 0; index < readSize; index++) {
			if (buf[index] == '\n') {
				/* When a line break, we append everything to the globalBuffer and then log it */
				AppendLog(buf + startBuf, &globalBuffer, &globalBufferCapacity, &globalBufferSize, index - 1 - startBuf);
				globalBuffer[globalBufferSize + 1] = '\0';
				__android_log_write(ANDROID_LOG_DEBUG, log_tag == NULL ? "UNKNOWN" : log_tag, globalBuffer);
				globalBufferSize = 0;
				startBuf = index;
			}
		}

		/* Append the remaining and do nothing else */
		if (readSize - startBuf > 0) {
			AppendLog(buf + startBuf, &globalBuffer, &globalBufferCapacity, &globalBufferSize, readSize - startBuf);
		}
	}

	free(globalBuffer);
	free(log_tag);
	return NULL;
}

int LoggingThreadRun(const char* appname) {
	setvbuf(stdout, 0, _IOLBF, 0); // make stdout line-buffered
	setvbuf(stderr, 0, _IONBF, 0); // make stderr unbuffered

	log_tag = strdup(appname);

	pipe(pfd);
	dup2(pfd[1], 1);
	dup2(pfd[1], 2);

	if (pthread_create(&loggingThread, 0, loggingFunction, 0) == -1) {
		__android_log_write(ANDROID_LOG_ERROR, log_tag == NULL ? "UNKNOWN" : log_tag,
											"Cannot spawn logging thread : logging from stdout / stderr won't show in logcat");
		return -1;
	}

	pthread_detach(loggingThread);
	return 0;
}
#endif