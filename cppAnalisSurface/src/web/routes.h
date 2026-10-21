#pragma once
#include <httplib.h>

namespace cas {
class Service;
// Registers the HTTP API. Contract: docs in README.md (compatible hot-path endpoints of analisSurface
// plus native /settings endpoints).
void registerRoutes(httplib::Server& svr, Service& service);
}
