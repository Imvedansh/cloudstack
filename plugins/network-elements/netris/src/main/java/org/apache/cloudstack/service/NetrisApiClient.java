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
package org.apache.cloudstack.service;

import io.netris.ApiException;
import io.netris.model.GetSiteBody;
import io.netris.model.VPCListing;
import io.netris.model.response.TenantResponse;
import org.apache.cloudstack.agent.api.CreateNetrisVnetCommand;
import org.apache.cloudstack.agent.api.CreateNetrisVpcCommand;
import org.apache.cloudstack.agent.api.DeleteNetrisVnetCommand;
import org.apache.cloudstack.agent.api.DeleteNetrisVpcCommand;
import org.apache.cloudstack.agent.api.SetupNetrisPublicRangeCommand;

import java.util.List;

public interface NetrisApiClient {
    boolean isSessionAlive();
    List<GetSiteBody> listSites();
    List<VPCListing> listVPCs();
    List<TenantResponse> listTenants() throws ApiException;

    /**
     * Create a VPC on CloudStack creates the following Netris resources:
     * - Create a Netris VPC with the VPC name
     * - Create an IPAM Allocation for the created Netris VPC using the Prefix = VPC CIDR
     */
    boolean createVpc(CreateNetrisVpcCommand cmd);

    /**
     * Delete a VPC on CloudStack removes the following Netris resources:
     * - Delete the IPAM Allocation for the VPC using the Prefix = VPC CIDR
     * - Delete a Netris VPC with the VPC name
     */
    boolean deleteVpc(DeleteNetrisVpcCommand cmd);

    boolean createVnet(CreateNetrisVnetCommand cmd);

    boolean deleteVnet(DeleteNetrisVnetCommand cmd);

    /**
     * Check and create zone level Netris Public range in the following manner:
     * - Check the IPAM allocation for the zone super CIDR. In case it doesn't exist, create it
     * - Check the IPAM subnet for NAT purpose for the range start-end. In case it doesn't exist, create it
     */
    boolean setupZoneLevelPublicRange(SetupNetrisPublicRangeCommand cmd);
}
