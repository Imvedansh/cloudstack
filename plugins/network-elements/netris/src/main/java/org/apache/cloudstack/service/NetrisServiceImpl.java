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

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.dc.VlanVO;
import com.cloud.dc.dao.VlanDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.network.IpAddress;
import com.cloud.network.Ipv6GuestPrefixSubnetNetworkMapVO;
import com.cloud.network.Network;
import com.cloud.network.Networks;
import com.cloud.network.SDNProviderNetworkRule;
import com.cloud.network.dao.Ipv6GuestPrefixSubnetNetworkMapDao;
import com.cloud.network.dao.NetrisProviderDao;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.dao.PhysicalNetworkDao;
import com.cloud.network.dao.PhysicalNetworkVO;
import com.cloud.network.element.NetrisProviderVO;
import com.cloud.network.netris.NetrisService;
import com.cloud.network.vpc.Vpc;
import com.cloud.network.vpc.dao.VpcDao;
import io.netris.model.NatPostBody;
import org.apache.cloudstack.agent.api.CreateNetrisACLCommand;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.net.NetUtils;
import inet.ipaddr.IPAddress;
import inet.ipaddr.IPAddressString;
import org.apache.cloudstack.agent.api.AddOrUpdateNetrisStaticRouteCommand;
import org.apache.cloudstack.agent.api.CreateNetrisVnetCommand;
import org.apache.cloudstack.agent.api.CreateNetrisVpcCommand;
import org.apache.cloudstack.agent.api.CreateOrUpdateNetrisNatCommand;
import org.apache.cloudstack.agent.api.DeleteNetrisACLCommand;
import org.apache.cloudstack.agent.api.DeleteNetrisNatRuleCommand;
import org.apache.cloudstack.agent.api.DeleteNetrisStaticRouteCommand;
import org.apache.cloudstack.agent.api.DeleteNetrisVnetCommand;
import org.apache.cloudstack.agent.api.DeleteNetrisVpcCommand;
import org.apache.cloudstack.agent.api.NetrisAnswer;
import org.apache.cloudstack.agent.api.NetrisCommand;
import org.apache.cloudstack.agent.api.ReleaseNatIpCommand;
import org.apache.cloudstack.agent.api.SetupNetrisPublicRangeCommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;
import com.cloud.network.netris.NetrisNetworkRule;
import org.apache.cloudstack.resource.NetrisResourceObjectUtils;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.inject.Inject;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

public class NetrisServiceImpl implements NetrisService, Configurable {

    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    private NetrisProviderDao netrisProviderDao;
    @Inject
    private NetworkDao networkDao;
    @Inject
    private VpcDao vpcDao;
    @Inject
    private AgentManager agentMgr;
    @Inject
    private PhysicalNetworkDao physicalNetworkDao;
    @Inject
    private VlanDao vlanDao;
    @Inject
    private Ipv6GuestPrefixSubnetNetworkMapDao  ipv6PrefixNetworkMapDao;

    @Override
    public String getConfigComponentName() {
        return NetrisService.class.getSimpleName();
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey[0];
    }

    private NetrisProviderVO getZoneNetrisProvider(long zoneId) {
        NetrisProviderVO netrisProviderVO = netrisProviderDao.findByZoneId(zoneId);
        if (netrisProviderVO == null) {
            logger.error("No Netris controller was found!");
            throw new InvalidParameterValueException("Failed to find a Netris controller");
        }
        return netrisProviderVO;
    }

    private NetrisAnswer sendNetrisCommand(NetrisCommand cmd, long zoneId) {
        NetrisProviderVO netrisProviderVO = getZoneNetrisProvider(zoneId);
        Answer answer = agentMgr.easySend(netrisProviderVO.getHostId(), cmd);
        if (answer == null || !answer.getResult()) {
            logger.error("Netris API Command failed");
            throw new InvalidParameterValueException("Failed API call to Netris controller");
        }
        return (NetrisAnswer) answer;
    }

    /**
     * Calculate the minimum CIDR subnet containing the IP range (using the library: <a href="https://github.com/seancfoley/IPAddress">IPAddress</a>)
     * From: <a href="https://github.com/seancfoley/IPAddress/wiki/Code-Examples-3:-Subnetting-and-Other-Subnet-Operations#from-start-and-end-address-get-single-cidr-block-covering-both">Example</a>
     * @param ipRange format: startIP-endIP
     * @return the minimum CIDR containing the IP range
     */
    protected String calculateSubnetCidrFromIpRange(String ipRange) {
        if (StringUtils.isBlank(ipRange) || !ipRange.contains("-")) {
            return null;
        }
        String[] rangeArray = ipRange.split("-");
        String startIp = rangeArray[0];
        String endIp = rangeArray[1];
        IPAddress startIpAddress = new IPAddressString(startIp).getAddress();
        IPAddress endIpAddress = new IPAddressString(endIp).getAddress();
        return startIpAddress.coverWithPrefixBlock(endIpAddress).toPrefixLengthString();
    }

    /**
     * Prepare the Netris Public Range to be used by CloudStack after the zone is created and the Netris provider is added
     */
    private Pair<String, String> getAllocationAndSubnet(String gateway, String netmask, String ipRange) {
        String superCidr = NetUtils.getCidrFromGatewayAndNetmask(gateway, netmask);
        String subnetNatCidr = calculateSubnetCidrFromIpRange(ipRange);
        return new Pair<>(superCidr, subnetNatCidr);
    }

    @Override
    public boolean createIPAMAllocationsForZoneLevelPublicRanges(long zoneId) {
        List<PhysicalNetworkVO> physicalNetworks = physicalNetworkDao.listByZoneAndTrafficType(zoneId, Networks.TrafficType.Public);
        physicalNetworks = physicalNetworks.stream().filter(x -> x.getIsolationMethods().contains(Network.Provider.Netris.getName())).collect(Collectors.toList());
        if (CollectionUtils.isEmpty(physicalNetworks)) {
            return false;
        }

        List<VlanVO> providerVlanIds = vlanDao.listVlansForExternalNetworkProvider(zoneId, ApiConstants.NETRIS_DETAIL_KEY);
        List<Long> vlanDbIds = providerVlanIds.stream().map(VlanVO::getId).collect(Collectors.toList());
        if (CollectionUtils.isEmpty(vlanDbIds)) {
            String msg = "Cannot find a public IP range VLAN range for the Netris Public traffic";
            logger.error(msg);
            throw new CloudRuntimeException(msg);
        }
        for (Long vlanDbId : vlanDbIds) {
            VlanVO vlanRecord = vlanDao.findById(vlanDbId);

            String gateway = Objects.nonNull(vlanRecord.getVlanGateway()) ? vlanRecord.getVlanGateway() : vlanRecord.getIp6Gateway();
            String netmask = vlanRecord.getVlanNetmask();
            String ipRange = vlanRecord.getIpRange();
            String ip6Cidr = vlanRecord.getIp6Cidr();
            SetupNetrisPublicRangeCommand cmd = null;
            if (NetUtils.isValidIp4(gateway)) {
                Pair<String, String> allocationAndSubnet = getAllocationAndSubnet(gateway, netmask, ipRange);
                cmd = new SetupNetrisPublicRangeCommand(zoneId, allocationAndSubnet.first(), allocationAndSubnet.second());
            } else if (NetUtils.isValidIp6(gateway)) {
                cmd = new SetupNetrisPublicRangeCommand(zoneId, ip6Cidr, ip6Cidr);
            }

            if (cmd == null) {
                throw new CloudRuntimeException("Incorrect gateway and netmask details provided for the Netris Public IP range setup");
            }
            NetrisAnswer answer = sendNetrisCommand(cmd, zoneId);
            if (!answer.getResult()) {
                throw new CloudRuntimeException("Netris Public IP Range setup failed, please check the logs");
            }
        }

        return true;
    }

    @Override
    public boolean createVpcResource(long zoneId, long accountId, long domainId, Long vpcId, String vpcName, boolean sourceNatEnabled, String cidr, boolean isVpc) {
        CreateNetrisVpcCommand cmd = new CreateNetrisVpcCommand(zoneId, accountId, domainId, vpcName, cidr, vpcId, isVpc);
        NetrisAnswer answer = sendNetrisCommand(cmd, zoneId);
        return answer.getResult();
    }

    @Override
    public boolean deleteVpcResource(long zoneId, long accountId, long domainId, Vpc vpc) {
        DeleteNetrisVpcCommand cmd = new DeleteNetrisVpcCommand(zoneId, accountId, domainId, vpc.getName(), vpc.getCidr(), vpc.getId(), true);
        NetrisAnswer answer = sendNetrisCommand(cmd, zoneId);
        return answer.getResult();
    }

    @Override
    public boolean createVnetResource(Long zoneId, long accountId, long domainId, String vpcName, Long vpcId, String networkName, Long networkId, String cidr) {
        NetworkVO network = networkDao.findById(networkId);
        String vxlanId = Networks.BroadcastDomainType.getValue(network.getBroadcastUri());
        CreateNetrisVnetCommand cmd = new CreateNetrisVnetCommand(zoneId, accountId, domainId, vpcName, vpcId, networkName, networkId, cidr, network.getGateway(), !Objects.isNull(vpcId));
        cmd.setVxlanId(Integer.parseInt(vxlanId));
        NetrisProviderVO netrisProvider = netrisProviderDao.findByZoneId(zoneId);
        cmd.setNetrisTag(netrisProvider.getNetrisTag());
        if (Objects.nonNull(networkId)) {
            Ipv6GuestPrefixSubnetNetworkMapVO ipv6PrefixNetworkMapVO = ipv6PrefixNetworkMapDao.findByNetworkId(networkId);
            if (Objects.nonNull(ipv6PrefixNetworkMapVO)) {
                cmd.setIpv6Cidr(ipv6PrefixNetworkMapVO.getSubnet());
            }
        }
        NetrisAnswer answer = sendNetrisCommand(cmd, zoneId);
        return answer.getResult();
    }

    @Override
    public boolean deleteVnetResource(long zoneId, long accountId, long domainId, String vpcName, Long vpcId, String networkName, Long networkId, String cidr) {
        DeleteNetrisVnetCommand cmd = new DeleteNetrisVnetCommand(zoneId, accountId, domainId, networkName, networkId, vpcName, vpcId, cidr, Objects.nonNull(vpcName));
        NetrisAnswer answer = sendNetrisCommand(cmd, zoneId);
        return answer.getResult();
    }

    @Override
    public boolean createSnatRule(long zoneId, long accountId, long domainId, String vpcName, long vpcId, String networkName, long networkId, boolean isForVpc, String vpcCidr, String snatIP) {
        CreateOrUpdateNetrisNatCommand cmd = new CreateOrUpdateNetrisNatCommand(zoneId, accountId, domainId, vpcName, vpcId, networkName, networkId, isForVpc, vpcCidr);
        cmd.setNatIp(snatIP);
        cmd.setNatRuleType("SNAT");
        String suffix;
        if (isForVpc) {
            suffix = String.valueOf(vpcId); // D1-A1-Z1-V25-SNAT
        } else {
            suffix = String.valueOf(networkId); // D1-A1-Z1-N25-SNAT
        }
        cmd.setProtocol(NatPostBody.ProtocolEnum.ALL.getValue());
        cmd.setState(NatPostBody.StateEnum.ENABLED.getValue());
        String snatRuleName = NetrisResourceObjectUtils.retrieveNetrisResourceObjectName(cmd, NetrisResourceObjectUtils.NetrisObjectType.SNAT, suffix);
        cmd.setNatRuleName(snatRuleName);
        NetrisAnswer answer = sendNetrisCommand(cmd, zoneId);
        return answer.getResult();
    }

    @Override
    public boolean createPortForwardingRule(long zoneId, long accountId, long domainId, String vpcName, long vpcId, String networkName,
                                            Long networkId, boolean isForVpc, String vpcCidr, SDNProviderNetworkRule networkRule) {
        CreateOrUpdateNetrisNatCommand cmd = new CreateOrUpdateNetrisNatCommand(zoneId, accountId, domainId, vpcName, vpcId,
                networkName, networkId, isForVpc, vpcCidr);
        cmd.setProtocol(networkRule.getProtocol().toLowerCase(Locale.ROOT));
        cmd.setDestinationAddress(networkRule.getPublicIp());
        cmd.setDestinationPort(networkRule.getPublicPort());
        cmd.setSourceAddress(networkRule.getVmIp());
        cmd.setSourcePort(networkRule.getPrivatePort());
        cmd.setState(NatPostBody.StateEnum.ENABLED.getValue());
        String ruleName = NetrisResourceObjectUtils.retrieveNetrisResourceObjectName(cmd, NetrisResourceObjectUtils.NetrisObjectType.DNAT,
                String.valueOf(vpcId), String.format("R%s", networkRule.getRuleId()));
        cmd.setNatRuleName(ruleName);
        cmd.setNatRuleType("DNAT");

        NetrisAnswer answer = sendNetrisCommand(cmd, zoneId);
        return answer.getResult();
    }

    @Override
    public boolean deletePortForwardingRule(long zoneId, long accountId, long domainId, String vpcName, Long vpcId, String networkName, Long networkId, boolean isForVpc, String vpcCidr, SDNProviderNetworkRule networkRule) {
        DeleteNetrisNatRuleCommand cmd = new DeleteNetrisNatRuleCommand(zoneId, accountId, domainId, vpcName, vpcId, networkName, networkId, isForVpc);
        String ruleName = NetrisResourceObjectUtils.retrieveNetrisResourceObjectName(cmd, NetrisResourceObjectUtils.NetrisObjectType.DNAT,
                String.valueOf(vpcId), String.format("R%s", networkRule.getRuleId()));
        cmd.setNatRuleType("DNAT");
        cmd.setNatRuleName(ruleName);
        NetrisAnswer answer = sendNetrisCommand(cmd, zoneId);
        return answer.getResult();
    }

    @Override
    public boolean updateVpcSourceNatIp(Vpc vpc, IpAddress address) {
        if (vpc == null || address == null) {
            return false;
        }
        long accountId = vpc.getAccountId();
        long domainId = vpc.getDomainId();
        long zoneId = vpc.getZoneId();
        long vpcId = vpc.getId();
        String vpcName = vpc.getName();

        logger.debug("Updating the source NAT IP for Netris VPC {} to IP: {}", vpc.getName(), address.getAddress().addr());

        CreateOrUpdateNetrisNatCommand cmd = new CreateOrUpdateNetrisNatCommand(zoneId, accountId, domainId, vpcName, vpcId, null, null, true, address.getAddress().addr());
        cmd.setNatRuleType("SNAT");
        String snatRuleName = NetrisResourceObjectUtils.retrieveNetrisResourceObjectName(cmd, NetrisResourceObjectUtils.NetrisObjectType.SNAT, String.valueOf(vpcId));
        cmd.setNatRuleName(snatRuleName);
        NetrisAnswer answer = sendNetrisCommand(cmd, zoneId);
        if (!answer.getResult()) {
            logger.error("Could not update the source NAT IP address for VPC {}: {}", vpc.getName(), answer.getDetails());
            return false;
        }
        return answer.getResult();
    }

    @Override
    public boolean createStaticNatRule(long zoneId, long accountId, long domainId, String networkResourceName, Long networkResourceId, boolean isForVpc, String vpcCidr, String staticNatIp, String vmIp) {
        String vpcName = null;
        String networkName = null;
        Long vpcId = null;
        Long networkId = null;
        if (isForVpc) {
            vpcName = networkResourceName;
            vpcId = networkResourceId;
        } else {
            networkName = networkResourceName;
            networkId = networkResourceId;
        }
        CreateOrUpdateNetrisNatCommand cmd = new CreateOrUpdateNetrisNatCommand(zoneId, accountId, domainId, vpcName, vpcId, networkName, networkId, isForVpc, vpcCidr);
        cmd.setNatRuleType("STATICNAT");
        cmd.setNatIp(staticNatIp);
        cmd.setVmIp(vmIp);
        String suffix = getResourceSuffix(vpcId, networkId, isForVpc);
        String dnatRuleName = NetrisResourceObjectUtils.retrieveNetrisResourceObjectName(cmd, NetrisResourceObjectUtils.NetrisObjectType.STATICNAT, suffix);
        cmd.setNatRuleName(dnatRuleName);
        NetrisAnswer answer = sendNetrisCommand(cmd, zoneId);
        return answer.getResult();
    }

    @Override
    public boolean deleteStaticNatRule(long zoneId, long accountId, long domainId, String networkResourceName, Long networkResourceId, boolean isForVpc, String staticNatIp) {
        String vpcName = null;
        String networkName = null;
        Long vpcId = null;
        Long networkId = null;
        if (isForVpc) {
            vpcName = networkResourceName;
            vpcId = networkResourceId;
        } else {
            networkName = networkResourceName;
            networkId = networkResourceId;
        }
        DeleteNetrisNatRuleCommand cmd = new DeleteNetrisNatRuleCommand(zoneId, accountId, domainId, vpcName, vpcId, networkName, networkId, isForVpc);
        String suffix = getResourceSuffix(vpcId, networkId, isForVpc);
        String dnatRuleName = NetrisResourceObjectUtils.retrieveNetrisResourceObjectName(cmd, NetrisResourceObjectUtils.NetrisObjectType.STATICNAT, suffix);
        cmd.setNatRuleName(dnatRuleName);
        cmd.setNatRuleType("STATICNAT");
        cmd.setNatIp(staticNatIp);
        NetrisAnswer answer = sendNetrisCommand(cmd, zoneId);
        return answer.getResult();
    }

    @Override
    public boolean addFirewallRules(Network network, List<NetrisNetworkRule> firewallRules) {
        Long zoneId = network.getDataCenterId();
        Long accountId = network.getAccountId();
        Long domainId = network.getDomainId();
        Long vpcId = network.getVpcId();
        Long networkId = network.getId();
        String vpcName = null;
        Vpc vpc = null;
        if (Objects.nonNull(vpcId)) {
            vpc = vpcDao.findById(vpcId);
            if (Objects.nonNull(vpc)) {
                vpcName = vpc.getName();
            }
        }
        String networkName = network.getName();
        NetrisNetworkRule rule = firewallRules.get(0);
        SDNProviderNetworkRule baseNetworkRule = rule.getBaseRule();
        String trafficType = baseNetworkRule.getTrafficType().toUpperCase(Locale.ROOT);
        String sourcePrefix;
        String destinationPrefix;
        if ("INGRESS".equals(trafficType)) {
            sourcePrefix = baseNetworkRule.getSourceCidrList().get(0);
            destinationPrefix = network.getCidr();
        } else {
            sourcePrefix = network.getCidr();
            destinationPrefix = baseNetworkRule.getSourceCidrList().get(0);
        }
        String srcPort;
        String dstPort;
        if (baseNetworkRule.getPrivatePort().contains("-")) {
            srcPort = baseNetworkRule.getPrivatePort().split("-")[0];
            dstPort = baseNetworkRule.getPrivatePort().split("-")[1];
        } else {
            srcPort = dstPort = baseNetworkRule.getPrivatePort();
        }
        CreateNetrisACLCommand cmd = new CreateNetrisACLCommand(zoneId, accountId, domainId, networkName, networkId,
                vpcName, vpcId, Objects.nonNull(vpcId), rule.getAclAction().name().toLowerCase(Locale.ROOT), getPrefix(sourcePrefix), getPrefix(destinationPrefix),
                "null".equals(srcPort) ? 1 : Integer.parseInt(srcPort),
                "null".equals(dstPort) ? 65535 : Integer.parseInt(dstPort), baseNetworkRule.getProtocol());
        String aclName = String.format("V%s-N%s-ACL%s", vpcId, networkId, rule.getBaseRule().getRuleId());
        String netrisAclName = NetrisResourceObjectUtils.retrieveNetrisResourceObjectName(cmd, NetrisResourceObjectUtils.NetrisObjectType.ACL, aclName);
        cmd.setNetrisAclName(netrisAclName);
        cmd.setReason(rule.getReason());
        if ("ICMP".equals(baseNetworkRule.getProtocol())) {
            cmd.setIcmpType(baseNetworkRule.getIcmpType());
        }
        NetrisAnswer answer = sendNetrisCommand(cmd, zoneId);
        return answer.getResult();
    }

    private String getPrefix(String prefix) {
        if ("ANY".equals(prefix)) {
            return NetUtils.ALL_IP4_CIDRS;
        }
        return prefix;
    }

    @Override
    public boolean deleteFirewallRules(Network network, List<NetrisNetworkRule> firewallRules) {
        long zoneId = network.getDataCenterId();
        Long accountId = network.getAccountId();
        Long domainId = network.getDomainId();
        String networkName = network.getName();
        Long networkId = network.getId();
        String vpcName = null;
        Long vpcId = network.getVpcId();
        if (Objects.nonNull(vpcId)) {
            vpcName = vpcDao.findById(vpcId).getName();
        }
        DeleteNetrisACLCommand cmd = new DeleteNetrisACLCommand(zoneId, accountId, domainId, networkName, networkId, Objects.nonNull(network.getVpcId()), vpcId, vpcName);
        List<String> aclRuleNames = firewallRules.stream()
                .map(rule -> {
                    String aclName = String.format("V%s-N%s-ACL%s", vpcId, networkId, rule.getBaseRule().getRuleId());
                    return NetrisResourceObjectUtils.retrieveNetrisResourceObjectName(cmd, NetrisResourceObjectUtils.NetrisObjectType.ACL, aclName);
                })
                .collect(Collectors.toList());
        cmd.setAclRuleNames(aclRuleNames);

        NetrisAnswer answer = sendNetrisCommand(cmd, zoneId);
        return answer.getResult();
    }

    public boolean addOrUpdateStaticRoute(long zoneId, long accountId, long domainId, String networkResourceName, Long networkResourceId, boolean isForVpc, String prefix, String nextHop, Long routeId, boolean updateRoute) {
        AddOrUpdateNetrisStaticRouteCommand cmd = new AddOrUpdateNetrisStaticRouteCommand(zoneId, accountId, domainId, networkResourceName, networkResourceId, isForVpc, prefix, nextHop, routeId, updateRoute);
        NetrisAnswer answer = sendNetrisCommand(cmd, zoneId);
        return answer.getResult();
    }

    @Override
    public boolean deleteStaticRoute(long zoneId, long accountId, long domainId, String networkResourceName, Long networkResourceId, boolean isForVpc, String prefix, String nextHop, Long routeId) {
        DeleteNetrisStaticRouteCommand cmd = new DeleteNetrisStaticRouteCommand(zoneId, accountId, domainId, networkResourceName, networkResourceId, isForVpc, prefix, nextHop, routeId);
        NetrisAnswer answer = sendNetrisCommand(cmd, zoneId);
        return answer.getResult();
    }

    @Override
    public boolean releaseNatIp(long zoneId, String publicIp) {
        ReleaseNatIpCommand cmd = new ReleaseNatIpCommand(zoneId, null, null, null, null, false, publicIp);
        NetrisAnswer answer = sendNetrisCommand(cmd, zoneId);
        return answer.getResult();
    }

    private String getResourceSuffix(Long vpcId, Long networkId, boolean isForVpc) {
        String suffix;
        if (isForVpc) {
            suffix = String.valueOf(vpcId); // D1-A1-Z1-V25-STATICNAT or D1-A1-Z1-V25-SNAT
        } else {
            suffix = String.valueOf(networkId); // D1-A1-Z1-N25-STATICNAT or D1-A1-Z1-N25-SNAT
        }
        return suffix;
    }
}
