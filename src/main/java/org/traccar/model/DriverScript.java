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
package org.traccar.model;

import org.traccar.storage.StorageName;

import java.util.Date;

@StorageName("tc_driver_scripts")
public class DriverScript extends BaseModel {

    private String fileName;

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    private String hash;

    public String getHash() {
        return hash;
    }

    public void setHash(String hash) {
        this.hash = hash;
    }

    private boolean enabled;

    public boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    private Date discoveredTime;

    public Date getDiscoveredTime() {
        return discoveredTime;
    }

    public void setDiscoveredTime(Date discoveredTime) {
        this.discoveredTime = discoveredTime;
    }

    private Date lastSeenTime;

    public Date getLastSeenTime() {
        return lastSeenTime;
    }

    public void setLastSeenTime(Date lastSeenTime) {
        this.lastSeenTime = lastSeenTime;
    }

    private Date loadedTime;

    public Date getLoadedTime() {
        return loadedTime;
    }

    public void setLoadedTime(Date loadedTime) {
        this.loadedTime = loadedTime;
    }

    private String error;

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

}
