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

import com.cloud.dc.DataCenter;
import com.cloud.deploy.DeployDestination;
import com.cloud.deploy.DeploymentPlan;
import com.cloud.domain.DomainVO;
import com.cloud.exception.InsufficientAddressCapacityException;
import com.cloud.exception.InsufficientVirtualNetworkCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.network.Network;
import com.cloud.network.NetworkMigrationResponder;
import com.cloud.network.Networks;
import com.cloud.network.PhysicalNetwork;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.dao.PhysicalNetworkVO;
import com.cloud.network.guru.GuestNetworkGuru;
import com.cloud.network.netris.NetrisService;
import com.cloud.network.vpc.VpcVO;
import com.cloud.offering.NetworkOffering;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.user.Account;
import com.cloud.utils.db.DB;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.NicProfile;
import com.cloud.vm.ReservationContext;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachineProfile;

import javax.inject.Inject;
import java.util.Objects;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

public class NetrisGuestNetworkGuru  extends GuestNetworkGuru implements NetworkMigrationResponder {

    @Inject
    private NetrisService netrisService;

    public NetrisGuestNetworkGuru() {
        super();
        _isolationMethods = new PhysicalNetwork.IsolationMethod[] {new PhysicalNetwork.IsolationMethod("Netris")};
    }

    @Override
    public boolean canHandle(NetworkOffering offering, DataCenter.NetworkType networkType,
                             PhysicalNetwork physicalNetwork) {
        return networkType == DataCenter.NetworkType.Advanced && isMyTrafficType(offering.getTrafficType())
                && isMyIsolationMethod(physicalNetwork) && (NetworkOffering.NetworkMode.ROUTED.equals(offering.getNetworkMode())
                || (networkOfferingServiceMapDao.isProviderForNetworkOffering(
                offering.getId(), Network.Provider.Netris) && NetworkOffering.NetworkMode.NATTED.equals(offering.getNetworkMode())));
    }

    @Override
    public Network design(NetworkOffering offering, DeploymentPlan plan, Network userSpecified, String name, Long vpcId, Account owner) {
        PhysicalNetworkVO physnet = _physicalNetworkDao.findById(plan.getPhysicalNetworkId());
        DataCenter dc = _dcDao.findById(plan.getDataCenterId());

        if (!canHandle(offering, dc.getNetworkType(), physnet)) {
            logger.debug("Refusing to design this network");
            return null;
        }

        NetworkVO network = (NetworkVO) super.design(offering, plan, userSpecified, name, vpcId, owner);
        if (network == null) {
            return null;
        }
        network.setBroadcastDomainType(Networks.BroadcastDomainType.Netris);

        if (userSpecified != null) {
            if ((userSpecified.getIp6Cidr() == null && userSpecified.getIp6Gateway() != null) || (
                    userSpecified.getIp6Cidr() != null && userSpecified.getIp6Gateway() == null)) {
                throw new InvalidParameterValueException("cidrv6 and gatewayv6 must be specified together.");
            }

            if (userSpecified.getIp6Cidr() != null) {
                network.setIp6Cidr(userSpecified.getIp6Cidr());
                network.setIp6Gateway(userSpecified.getIp6Gateway());
            }
        }

        network.setBroadcastDomainType(Networks.BroadcastDomainType.Netris);
        network.setState(Network.State.Allocated);

        NetworkVO implemented = new NetworkVO(network.getTrafficType(), network.getMode(),
                network.getBroadcastDomainType(), network.getNetworkOfferingId(), Network.State.Implemented,
                network.getDataCenterId(), network.getPhysicalNetworkId(), offering.isRedundantRouter());
        implemented.setAccountId(owner.getAccountId());

        if (network.getGateway() != null) {
            implemented.setGateway(network.getGateway());
        }

        if (network.getCidr() != null) {
            implemented.setCidr(network.getCidr());
        }

        if (vpcId != null) {
            implemented.setVpcId(vpcId);
        }

        if (name != null) {
            implemented.setName(name);
        }
        implemented.setBroadcastUri(Networks.BroadcastDomainType.Netris.toUri("netris"));

        return network;
    }

    @Override
    public void setup(Network network, long networkId) {
        try {
            NetworkVO designedNetwork  = _networkDao.findById(networkId);
            long zoneId = network.getDataCenterId();
            DataCenter zone = _dcDao.findById(zoneId);
            if (isNull(zone)) {
                throw new CloudRuntimeException(String.format("Failed to find zone with id: %s", zoneId));
            }
            createNetrisVnet(designedNetwork, zone);
        } catch (Exception ex) {
            throw new CloudRuntimeException("unable to create Netris network " + network.getUuid() + "due to: " + ex.getMessage());
        }
    }

    @Override
    @DB
    public void deallocate(Network config, NicProfile nic, VirtualMachineProfile vm) {
        // Do nothing
    }

    @Override
    public Network implement(Network network, NetworkOffering offering, DeployDestination dest,
                             ReservationContext context) {
        NetworkVO implemented = new NetworkVO(network.getTrafficType(), network.getMode(),
                network.getBroadcastDomainType(), network.getNetworkOfferingId(), Network.State.Implemented,
                network.getDataCenterId(), network.getPhysicalNetworkId(), offering.isRedundantRouter());
        implemented.setAccountId(network.getAccountId());

        if (network.getGateway() != null) {
            implemented.setGateway(network.getGateway());
        }

        if (network.getCidr() != null) {
            implemented.setCidr(network.getCidr());
        }

        if (network.getVpcId() != null) {
            implemented.setVpcId(network.getVpcId());
        }

        if (network.getName() != null) {
            implemented.setName(network.getName());
        }
        implemented.setBroadcastUri(Networks.BroadcastDomainType.Netris.toUri("netris"));
        return implemented;
    }

    @Override
    public NicProfile allocate(Network network, NicProfile nic, VirtualMachineProfile vm) throws InsufficientVirtualNetworkCapacityException, InsufficientAddressCapacityException {
        NicProfile nicProfile = super.allocate(network, nic, vm);
        if (vm.getType() != VirtualMachine.Type.DomainRouter) {
            return nicProfile;
        }

        final DataCenter zone = _dcDao.findById(network.getDataCenterId());
        long zoneId = network.getDataCenterId();
        if (Objects.isNull(zone)) {
            String msg = String.format("Unable to find zone with id: %s", zoneId);
            logger.error(msg);
            throw new CloudRuntimeException(msg);
        }
        Account account = accountDao.findById(network.getAccountId());
        if (Objects.isNull(account)) {
            String msg = String.format("Unable to find account with id: %s", network.getAccountId());
            logger.error(msg);
            throw new CloudRuntimeException(msg);
        }
        VpcVO vpc = _vpcDao.findById(network.getVpcId());
        if (Objects.isNull(vpc)) {
            String msg = String.format("Unable to find VPC with id: %s, allocating for network %s", network.getVpcId(), network.getName());
            logger.debug(msg);
        }

        DomainVO domain = domainDao.findById(account.getDomainId());
        if (Objects.isNull(domain)) {
            String msg = String.format("Unable to find domain with id: %s", account.getDomainId());
            logger.error(msg);
            throw new CloudRuntimeException(msg);
        }

        NetworkOfferingVO networkOfferingVO = networkOfferingDao.findById(network.getNetworkOfferingId());

        if (isNull(network.getVpcId()) && networkOfferingVO.getNetworkMode().equals(NetworkOffering.NetworkMode.NATTED)) {
            // Netris Natted mode
        }

        return nicProfile;
    }

    @Override
    public boolean prepareMigration(NicProfile nic, Network network, VirtualMachineProfile vm, DeployDestination dest, ReservationContext context) {
        return false;
    }

    @Override
    public void rollbackMigration(NicProfile nic, Network network, VirtualMachineProfile vm, ReservationContext src, ReservationContext dst) {
        // Do nothing
    }

    @Override
    public void commitMigration(NicProfile nic, Network network, VirtualMachineProfile vm, ReservationContext src, ReservationContext dst) {
        // Do nothing
    }

    public void createNetrisVnet(NetworkVO networkVO, DataCenter zone) {
        Account account = accountDao.findById(networkVO.getAccountId());
        if (isNull(account)) {
            throw new CloudRuntimeException(String.format("Unable to find account with id: %s", networkVO.getAccountId()));
        }
        DomainVO domain = domainDao.findById(account.getDomainId());
        if (Objects.isNull(domain)) {
            String msg = String.format("Unable to find domain with id: %s", account.getDomainId());
            logger.error(msg);
            throw new CloudRuntimeException(msg);
        }
        String vpcName = null;
        Long vpcId = null;
        if (nonNull(networkVO.getVpcId())) {
            VpcVO vpc = _vpcDao.findById(networkVO.getVpcId());
            if (isNull(vpc)) {
                throw new CloudRuntimeException(String.format("Failed to find VPC network with id: %s", networkVO.getVpcId()));
            }
            vpcName = vpc.getName();
            vpcId = vpc.getId();
        } else {
            logger.debug(String.format("Creating a Tier 1 Gateway for the network %s before creating the NSX segment", networkVO.getName()));
            long networkOfferingId = networkVO.getNetworkOfferingId();
            NetworkOfferingVO networkOfferingVO = networkOfferingDao.findById(networkOfferingId);
            boolean isSourceNatSupported = !NetworkOffering.NetworkMode.ROUTED.equals(networkOfferingVO.getNetworkMode()) &&
                    networkOfferingServiceMapDao.areServicesSupportedByNetworkOffering(networkVO.getNetworkOfferingId(), Network.Service.SourceNat);
            boolean result = netrisService.createVpcResource(zone.getId(), networkVO.getAccountId(), networkVO.getDomainId(),
                    networkVO.getId(), networkVO.getName(), isSourceNatSupported, networkVO.getCidr(), false);
            if (!result) {
                String msg = String.format("Error creating Netris VPC for the network: %s", networkVO.getName());
                logger.error(msg);
                throw new CloudRuntimeException(msg);
            }
        }
        boolean result = netrisService.createVnetResource(zone.getId(), account.getId(), domain.getId(), vpcName, vpcId, networkVO.getName(), networkVO.getId(), networkVO.getCidr());
        if (!result) {
            throw new CloudRuntimeException("Failed to create Netris vNet resource for network: " + networkVO.getName());
        }
    }
}
