#ifndef CGSS_LOGGER_VIEWS_H
#define CGSS_LOGGER_VIEWS_H
#include "LiteCGSS/Common/Logger.h"

namespace cgss {
	using LoggerViews = Logger<ska::LogLevel::Debug>;
	LoggerViews& GetLoggerViews();
}

#endif