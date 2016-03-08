package org.batfish.representation.aws_vpcs;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.batfish.common.BatfishException;
import org.batfish.common.BatfishLogger;
import org.batfish.representation.IpAccessList;
import org.batfish.representation.IpAccessListLine;
import org.batfish.representation.IpProtocol;
import org.batfish.representation.LineAction;
import org.batfish.representation.Prefix;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

public class NetworkAcl implements AwsVpcEntity, Serializable {

   private static final long serialVersionUID = 1L;

   private List<NetworkAclEntry> _entries = new LinkedList<NetworkAclEntry>();

   private List<NetworkAclAssociation> _networkAclAssociations = new LinkedList<NetworkAclAssociation>();

   private String _networkAclId;

   private String _vpcId;

   public NetworkAcl(JSONObject jObj, BatfishLogger logger)
         throws JSONException {
      _networkAclId = jObj.getString(JSON_KEY_NETWORK_ACL_ID);
      _vpcId = jObj.getString(JSON_KEY_VPC_ID);

      JSONArray associations = jObj.getJSONArray(JSON_KEY_ASSOCIATIONS);
      InitAssociations(associations, logger);

      JSONArray entries = jObj.getJSONArray(JSON_KEY_ENTRIES);
      InitEntries(entries, logger);
   }

   private IpAccessList getAcl(boolean isEgress) {
      String listName = _networkAclId + (isEgress ? "_egress" : "_ingress");
      Map<Integer, IpAccessListLine> lineMap = new TreeMap<Integer, IpAccessListLine>();
      for (NetworkAclEntry entry : _entries) {
         if ((isEgress && entry.getIsEgress())
               || (!isEgress && !entry.getIsEgress())) {
            IpAccessListLine line = new IpAccessListLine();
            int key = entry.getRuleNumber();
            if (entry.getIsAllow()) {
               line.setAction(LineAction.ACCEPT);
            }
            else {
               line.setAction(LineAction.REJECT);
            }
            Prefix prefix = entry.getCidrBlock();
            if (!prefix.equals(Prefix.ZERO)) {
               if (isEgress) {
                  line.getDestinationIpRanges().add(prefix);
               }
               else {
                  line.getSourceIpRanges().add(prefix);
               }
            }
            IpProtocol protocol = IpPermissions.toIpProtocol(entry
                  .getProtocol());
            if (protocol != null) {
               line.getProtocols().add(protocol);
            }
            // TODO: handle ports: ALWAYS use destination port
            // range
            lineMap.put(key, line);
         }
      }
      List<IpAccessListLine> lines = new ArrayList<IpAccessListLine>();
      lines.addAll(lineMap.values());
      IpAccessList list = new IpAccessList(listName, lines);
      return list;
   }

   public List<NetworkAclAssociation> getAssociations() {
      return _networkAclAssociations;
   }

   public IpAccessList getEgressAcl() {
      return getAcl(true);
   }

   @Override
   public String getId() {
      return _networkAclId;
   }

   public IpAccessList getIngressAcl() {
      return getAcl(false);
   }

   public String getVpcId() {
      return _vpcId;
   }

   private void InitAssociations(JSONArray associations, BatfishLogger logger)
         throws JSONException {

      for (int index = 0; index < associations.length(); index++) {
         JSONObject childObject = associations.getJSONObject(index);
         _networkAclAssociations.add(new NetworkAclAssociation(childObject,
               logger));
      }
   }

   private void InitEntries(JSONArray entries, BatfishLogger logger)
         throws JSONException {

      for (int index = 0; index < entries.length(); index++) {
         JSONObject childObject = entries.getJSONObject(index);
         _entries.add(new NetworkAclEntry(childObject, logger));
      }

   }

}