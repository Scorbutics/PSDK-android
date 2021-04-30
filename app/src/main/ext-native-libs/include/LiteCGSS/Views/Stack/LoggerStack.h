#ifndef CGSS_LOGGER_STACK_H
#define CGSS_LOGGER_STACK_H
#include "LiteCGSS/Common/Logger.h"

namespace cgss {
	using LoggerStack = Logger<ska::LogLevel::Debug>;
	LoggerStack& GetLoggerStack();
}

#endif