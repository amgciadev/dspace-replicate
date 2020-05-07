package org.dspace.pack.bagit;

import java.io.IOException;
import java.sql.SQLException;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.common.collect.BiMap;
import com.google.common.collect.ImmutableBiMap;
import org.dspace.authorize.AuthorizeException;
import org.dspace.authorize.ResourcePolicy;
import org.dspace.authorize.factory.AuthorizeServiceFactory;
import org.dspace.authorize.service.AuthorizeService;
import org.dspace.authorize.service.ResourcePolicyService;
import org.dspace.content.DSpaceObject;
import org.dspace.content.packager.PackageException;
import org.dspace.content.packager.PackageUtils;
import org.dspace.core.Constants;
import org.dspace.core.Context;
import org.dspace.curate.Curator;
import org.dspace.eperson.EPerson;
import org.dspace.eperson.Group;
import org.dspace.eperson.factory.EPersonServiceFactory;
import org.dspace.eperson.service.EPersonService;
import org.dspace.eperson.service.GroupService;
import org.dspace.pack.bagit.xml.Element;
import org.dspace.pack.bagit.xml.Policy;
import org.dspace.pack.bagit.xml.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Operations for {@link ResourcePolicy} objects in BagIt bags
 *
 * Generates a {@link Policy} pojo for {@link DSpaceObject}s which can be easily serialized to XML
 *
 * @author mikejritter
 */
public class BagItPolicyUtil {

    private static final Logger logger = LoggerFactory.getLogger(BagItPolicyUtil.class);

    // ResourcePolicy XML Attributes
    private static final String RP_NAME = "rp-name";
    private static final String RP_ACTION = "rp-action";
    private static final String RP_CONTEXT = "rp-context";
    private static final String RP_END_DATE = "rp-end-date";
    private static final String RP_START_DATE = "rp-start-date";
    private static final String RP_IN_EFFECT = "rp-in-effect";
    private static final String RP_DESCRIPTION = "rp-description";

    // from METSRightsCrosswalk, determine if a RP is for a custom group or eperson
    private static final String MANAGED_GROUP = "MANAGED GROUP";
    private static final String ACADEMIC_USER = "ACADEMIC USER";

    private final DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");

    /**
     * Create a {@link Policy} for a {@link DSpaceObject}
     *
     * @param dso The {@link DSpaceObject} to get the {@link Policy} for
     * @return the {@link Policy}
     */
    public Policy getPolicy(final Context context, final DSpaceObject dso) throws IOException {
        final Policy policy = new Policy();
        final BiMap<Integer, String> actions = actionMapper().inverse();

        for (ResourcePolicy resourcePolicy : dso.getResourcePolicies()) {
            final Map<String, String> attributes = new HashMap<>();
            String username = null;

            // name and description
            attributes.put(RP_NAME, resourcePolicy.getRpName());
            attributes.put(RP_DESCRIPTION, resourcePolicy.getRpDescription());

            // in-effect = true by default, then needs checks on start + end date
            boolean inEffect = true;
            final Date endDate = resourcePolicy.getEndDate();
            final Date startDate = resourcePolicy.getStartDate();
            final Date now = new Date();
            if (startDate != null) {
                attributes.put(RP_START_DATE, dateFormat.format(startDate));
                if (startDate.after(now)) {
                    inEffect = false;
                }
            }
            if (endDate != null) {
                attributes.put(RP_END_DATE, dateFormat.format(endDate));
                if (endDate.before(now)) {
                    inEffect = false;
                }
            }
            attributes.put(RP_IN_EFFECT, Boolean.toString(inEffect));

            // attributes for determining if adding policies on a group + what type of group or policies for a user
            final Group group = resourcePolicy.getGroup();
            final EPerson ePerson = resourcePolicy.getEPerson();
            if (group != null) {
                final String groupName = group.getName();
                if (groupName.equals(Group.ANONYMOUS)) {
                    attributes.put(RP_CONTEXT, Group.ANONYMOUS);
                    username = groupName;
                } else if (groupName.equals(Group.ADMIN)) {
                    attributes.put(RP_CONTEXT, Group.ADMIN);
                    username = groupName;
                } else {
                    attributes.put(RP_CONTEXT, MANAGED_GROUP);
                    try {
                        username = PackageUtils.translateGroupNameForExport(context, groupName);
                    } catch (PackageException exception) {
                        // since this is called by a Packer, wrap the PackageException in an IOException so it can
                        // continue to be thrown up the stack
                        throw new IOException(exception.getMessage(), exception);
                    }
                }
            } else if (ePerson != null) {
                attributes.put(RP_CONTEXT, ACADEMIC_USER);
                username = ePerson.getEmail();
            } else {
                logger.warn("No EPerson or Group found for policy!");
            }

            final String action = actions.get(resourcePolicy.getAction());
            attributes.put(RP_ACTION, action);

            policy.addChild(new Value(username, attributes));
        }

        return policy;
    }

    /**
     * Register all policies found from {@link Policy#getChildren()} by mapping them to a new {@link ResourcePolicy}.
     * This operation will replace all existing ResourcePolicies for a given {@link DSpaceObject} unless there is an
     * error during the mapping from a {@link Value} to a {@link ResourcePolicy}.
     *
     * @param dSpaceObject the {@link DSpaceObject} to register policies for
     * @param policy the {@link Policy} pojo to create each {@link ResourcePolicy}
     */
    public void registerPolicies(final DSpaceObject dSpaceObject, final Policy policy)
        throws SQLException, AuthorizeException, PackageException {
        final GroupService groupService = EPersonServiceFactory.getInstance().getGroupService();
        final EPersonService ePersonService = EPersonServiceFactory.getInstance().getEPersonService();
        final AuthorizeService authorizeService = AuthorizeServiceFactory.getInstance().getAuthorizeService();
        final ResourcePolicyService resourcePolicyService = AuthorizeServiceFactory.getInstance().getResourcePolicyService();

        // Need to map policy children from List<Element> to List<ResourcePolicy>
        // then use the authorizationService to add all policies to the dso
        final List<ResourcePolicy> policies = new ArrayList<>();
        for (Element element : policy.getChildren()) {
            final Map<String, String> attributes = element.getAttributes();
            final ResourcePolicy resourcePolicy = resourcePolicyService.create(Curator.curationContext());

            final String rpName = attributes.get(RP_NAME);
            if (rpName != null) {
                resourcePolicy.setRpName(rpName);
            }

            final String rpDescription = attributes.get(RP_DESCRIPTION);
            if (rpDescription != null) {
                resourcePolicy.setRpDescription(rpDescription);
            }

            final String rpStartDate = attributes.get(RP_START_DATE);
            if (rpStartDate != null) {
                try {
                    final Date date = dateFormat.parse(rpStartDate);
                    resourcePolicy.setStartDate(date);
                } catch (ParseException ignored) {
                    logger.warn("Failed to parse rp-start-date. The date needs to be in the format 'yyyy-MM-dd'.");
                }
            }

            final String rpEndDate = attributes.get(RP_END_DATE);
            if (rpEndDate != null) {
                try {
                    final Date date = dateFormat.parse(rpEndDate);
                    resourcePolicy.setEndDate(date);
                } catch (ParseException ignored) {
                    logger.warn("Failed to parse rp-start-date. The date needs to be in the format 'yyyy-MM-dd'.");
                }
            }

            final String userContext = attributes.get(RP_CONTEXT);
            if (Group.ADMIN.equalsIgnoreCase(userContext)) {
                final Group group = groupService.findByName(Curator.curationContext(), Group.ADMIN);
                if (group == null) {
                    throw new PackageException("The Administrator Group is missing from the database.");
                }

                resourcePolicy.setGroup(group);
            } else if (Group.ANONYMOUS.equalsIgnoreCase(userContext)) {
                final Group group = groupService.findByName(Curator.curationContext(), Group.ANONYMOUS);
                if (group == null) {
                    throw new PackageException("The Anonymous Group is missing from the database.");
                }

                resourcePolicy.setGroup(group);
            } else if (MANAGED_GROUP.equalsIgnoreCase(userContext)) {
                final String groupName = PackageUtils.translateGroupNameForImport(Curator.curationContext(), element.getBody());
                final Group group = groupService.findByName(Curator.curationContext(), groupName);
                if (group == null) {
                    throw new PackageException("Could not find managed group " + groupName + " in the database");
                }

                resourcePolicy.setGroup(group);
            } else if (ACADEMIC_USER.equalsIgnoreCase(userContext)) {
                final String email = element.getBody();
                final EPerson ePerson = ePersonService.findByEmail(Curator.curationContext(), email);
                if (ePerson == null) {
                    throw new PackageException("Could not find ePerson " + email + " in the database");
                }

                resourcePolicy.setEPerson(ePerson);
            } else {
                // throw an exception as well?
                logger.warn("Cannot import policy with rp-context {}, value must be one of {}, {}, {}, or {}",
                            userContext, Group.ADMIN, Group.ANONYMOUS, MANAGED_GROUP, ACADEMIC_USER);
            }

            final Integer action = actionMapper().get(attributes.get(RP_ACTION));
            // exception if null?
            if (action != null) {
                resourcePolicy.setAction(action);
            }

            policies.add(resourcePolicy);
        }

        authorizeService.removeAllPolicies(Curator.curationContext(), dSpaceObject);
        authorizeService.addPolicies(Curator.curationContext(), policies, dSpaceObject);
    }

    /**
     * This is pretty light so just recreate it so it can be gc'd later
     *
     * @return the mapping between action String and Int representations
     */
    private BiMap<String, Integer> actionMapper() {
        return ImmutableBiMap.<String, Integer>builder()
                      .put("ADD", Constants.ADD)
                      .put("READ", Constants.READ)
                      .put("ADMIN", Constants.ADMIN)
                      .put("WRITE", Constants.WRITE)
                      .put("DELETE", Constants.DELETE)
                      .put("REMOVE", Constants.REMOVE)
                      .put("READ_ITEM", Constants.DEFAULT_ITEM_READ)
                      .put("READ_BITSTREAM", Constants.DEFAULT_BITSTREAM_READ)
            .build();
    }

}
