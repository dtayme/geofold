// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

/*
 * Copyright 2026 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.api.resource;

import org.traccar.api.BaseResource;
import org.traccar.driver.DriverRegistry;
import org.traccar.model.DriverScript;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Order;
import org.traccar.storage.query.Request;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.stream.Stream;

@Path("driver-scripts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DriverScriptResource extends BaseResource {

    @Inject
    private DriverRegistry driverRegistry;

    @GET
    public Stream<DriverScript> get() throws StorageException {
        permissionsService.checkAdmin(getUserId());
        return storage.getObjectsStream(DriverScript.class, new Request(
                new Columns.All(), null, new Order("fileName")));
    }

    @POST
    @Path("{id}/enable")
    public Response enable(@PathParam("id") long id) throws StorageException {
        return setEnabled(id, true);
    }

    @POST
    @Path("{id}/disable")
    public Response disable(@PathParam("id") long id) throws StorageException {
        return setEnabled(id, false);
    }

    private Response setEnabled(long id, boolean enabled) throws StorageException {
        permissionsService.checkAdmin(getUserId());

        DriverScript driverScript = storage.getObject(DriverScript.class, new Request(
                new Columns.All(), new Condition.Equals("id", id)));
        if (driverScript == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        driverScript.setEnabled(enabled);
        storage.updateObject(driverScript, new Request(
                new Columns.Include("enabled"),
                new Condition.Equals("id", id)));

        if (enabled) {
            driverRegistry.reload(driverScript.getFileName());
        } else {
            driverRegistry.unload(driverScript.getFileName());
        }

        return Response.ok(driverScript).build();
    }

}
