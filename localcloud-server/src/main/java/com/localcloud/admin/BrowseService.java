package com.localcloud.admin;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.annotation.Get;

import javax.sql.DataSource;

/**
 * Placeholder browse service for the LocalCloud dashboard.
 * In orchestrator mode, data browsing is not available because each
 * external emulator manages its own storage independently.
 * Registered at the {@code /_localcloud/browse} path prefix.
 */
public class BrowseService {

    // DataSource kept for constructor compatibility with LocalCloudApplication
    public BrowseService(DataSource dataSource) {
        // no-op in orchestrator mode
    }

    @Get("/{service}")
    public HttpResponse browse() {
        return notAvailable();
    }

    @Get("/{service}/{path:.*}")
    public HttpResponse browseWithPath() {
        return notAvailable();
    }

    private HttpResponse notAvailable() {
        return HttpResponse.of(HttpStatus.OK, MediaType.JSON,
                """
                {
                  "message": "Browse is not available in orchestrator mode. Each external emulator manages its own data store.",
                  "hint": "Use the emulator-specific APIs or CLI tools to inspect data."
                }
                """);
    }
}
