//
// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
//

package com.cloud.agent.api;

import com.cloud.host.Host;

import java.util.List;

public class ReadyCommand extends Command {
    private String _details;

    public ReadyCommand() {
        super();
    }

    private Long dcId;
    private Long hostId;
    private String hostUuid;
    private String hostName;
    private List<String> msHostList;
    private List<String> avoidMsHostList;
    private String lbAlgorithm;
    private Long lbCheckInterval;
    private Boolean enableHumanReadableSizes;
    private String arch;

    public ReadyCommand(Long dcId) {
        super();
        this.dcId = dcId;
    }

    public ReadyCommand(final Host host, boolean enableHumanReadableSizes) {
        this(host.getDataCenterId());
        this.hostId = host.getId();
        this.hostUuid = host.getUuid();
        this.hostName = host.getName();
        this.enableHumanReadableSizes = enableHumanReadableSizes;
    }

    public void setDetails(String details) {
        _details = details;
    }

    public String getDetails() {
        return _details;
    }

    public Long getDataCenterId() {
        return dcId;
    }

    @Override
    public boolean executeInSequence() {
        return true;
    }

    public Long getHostId() {
        return hostId;
    }

    public String getHostUuid() {
        return hostUuid;
    }

    public String getHostName() {
        return hostName;
    }

    public List<String> getMsHostList() {
        return msHostList;
    }

    public void setMsHostList(List<String> msHostList) {
        this.msHostList = msHostList;
    }

    public List<String> getAvoidMsHostList() {
        return avoidMsHostList;
    }

    public void setAvoidMsHostList(List<String> msHostList) {
        this.avoidMsHostList = avoidMsHostList;
    }

    public String getLbAlgorithm() {
        return lbAlgorithm;
    }

    public void setLbAlgorithm(String lbAlgorithm) {
        this.lbAlgorithm = lbAlgorithm;
    }

    public Long getLbCheckInterval() {
        return lbCheckInterval;
    }

    public void setLbCheckInterval(Long lbCheckInterval) {
        this.lbCheckInterval = lbCheckInterval;
    }

    public Boolean getEnableHumanReadableSizes() {
        return enableHumanReadableSizes;
    }

    public String getArch() {
        return arch;
    }

    public void setArch(String arch) {
        this.arch = arch;
    }
}
