/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.pack.bagit;

import static org.dspace.pack.PackerFactory.BAG_TYPE;
import static org.dspace.pack.PackerFactory.OBJECT_ID;
import static org.dspace.pack.PackerFactory.OBJECT_TYPE;
import static org.dspace.pack.PackerFactory.OBJFILE;
import static org.dspace.pack.PackerFactory.OWNER_ID;
import static org.dspace.pack.bagit.BagItAipWriter.BAG_AIP;
import static org.dspace.pack.bagit.BagItAipWriter.OBJ_TYPE_COLLECTION;
import static org.dspace.pack.bagit.BagItAipWriter.PROPERTIES_DELIMITER;
import static org.dspace.pack.bagit.BagItAipWriter.XML_NAME_KEY;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import org.dspace.authorize.AuthorizeException;
import org.dspace.content.Bitstream;
import org.dspace.content.Collection;
import org.dspace.content.Community;
import org.dspace.content.Item;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.packager.PackageException;
import org.dspace.content.service.CollectionService;
import org.dspace.content.service.ItemService;
import org.dspace.curate.Curator;
import org.dspace.pack.Packer;
import org.dspace.pack.PackerFactory;
import org.dspace.pack.bagit.xml.Element;
import org.dspace.pack.bagit.xml.metadata.Metadata;
import org.dspace.pack.bagit.xml.metadata.Value;
import org.dspace.pack.bagit.xml.policy.Policy;

/**
 * CollectionPacker packs and unpacks Collection AIPs in BagIt bags
 *
 * @author richardrodgers
 */
public class CollectionPacker implements Packer
{
    private CollectionService collectionService = ContentServiceFactory.getInstance().getCollectionService();
    private ItemService itemService = ContentServiceFactory.getInstance().getItemService();

    // NB - these values must remain synchronized with DB schema
    // they represent the persistent object state
    private static final String[] fields =
    {
        "name",
        "short_description",
        "introductory_text",
        "provenance_description",
        "license",
        "copyright_text",
        "side_bar_text"
    };

    private Collection collection = null;
    private String archFmt = null;

    public CollectionPacker(Collection collection, String archFmt)
    {
        this.collection = collection;
        this.archFmt = archFmt;
    }

    public Collection getCollection()
    {
        return collection;
    }

    public void setCollection(Collection collection)
    {
        this.collection = collection;
    }

    @Override
    public File pack(File packDir) throws AuthorizeException, IOException, SQLException {
        final BagItPolicyUtil policyUtil = new BagItPolicyUtil();
        final Bitstream logo = collection.getLogo();

        // collect the object.properties
        final List<String> objectProperties = new ArrayList<>();
        objectProperties.add(BAG_TYPE + PROPERTIES_DELIMITER + BAG_AIP);
        objectProperties.add(OBJECT_TYPE + PROPERTIES_DELIMITER + OBJ_TYPE_COLLECTION);
        objectProperties.add(OBJECT_ID + PROPERTIES_DELIMITER + collection.getHandle());
        final List<Community> communities = collection.getCommunities();
        if (!communities.isEmpty()) {
            final Community parent = communities.get(0);
            objectProperties.add(OWNER_ID + PROPERTIES_DELIMITER + parent.getHandle());
        }
        final Map<String, List<String>> properties = ImmutableMap.of(OBJFILE, objectProperties);

        // collect the xml metadata
        final Metadata metadata = new Metadata();
        for (String field : fields) {
            final String body = collectionService.getMetadata(collection, field);
            Value value = new Value();
            value.setName(field);
            value.setBody(body);
            metadata.addValue(value);
        }

        // collect xml policy
        final Policy policy = policyUtil.getPolicy(Curator.curationContext(), collection);

        return new BagItAipWriter(packDir, archFmt, properties).withLogo(logo)
            .withPolicy(policy)
            .withMetadata(metadata)
            .packageAip();
    }

    @Override
    public void unpack(File archive) throws AuthorizeException, IOException, SQLException {
        if (archive == null || !archive.exists()) {
            throw new IOException("Missing archive for collection: " + collection.getHandle());
        }

        final BagItPolicyUtil policyUtil = new BagItPolicyUtil();
        final BagItAipReader reader = new BagItAipReader(archive.toPath());
        reader.validateBag();

        final Metadata metadata = reader.readMetadata();
        for (Value value : metadata.getValues()) {
            collectionService.setMetadata(Curator.curationContext(), collection, value.getName(), value.getBody());
        }

        try {
            final Policy policy = reader.readPolicy();
            policyUtil.registerPolicies(collection, policy);
        } catch (PackageException e) {
            throw new IOException(e.getMessage(), e);
        }

        final Optional<Path> logo = reader.findLogo();
        if (logo.isPresent()) {
            try (InputStream logoStream = Files.newInputStream(logo.get())) {
                collectionService.setLogo(Curator.curationContext(), collection, logoStream);
            }
        }

        collectionService.update(Curator.curationContext(), collection);

        reader.clean();
    }

    @Override
    public long size(String method) throws SQLException
    {
        long size = 0L;
        // start with logo size, if present
        Bitstream logo = collection.getLogo();
        if (logo != null)
        {
            size += logo.getSize();
        }
        // proceed to items, unless 'norecurse' set
        if (! "norecurse".equals(method))
        {
            Iterator<Item> itemIter = itemService.findByCollection(Curator.curationContext(), collection);
            ItemPacker iPup = null;
            while (itemIter.hasNext())
            {
                if (iPup == null)
                {
                    iPup = (ItemPacker)PackerFactory.instance(itemIter.next());
                }
                else
                {
                    iPup.setItem(itemIter.next());
                }
                size += iPup.size(method);
            }
        }
        return size;
    }

    @Override
    public void setContentFilter(String filter)
    {
        // no-op
    }

    @Override
    public void setReferenceFilter(String filter)
    {
        throw new UnsupportedOperationException("Not supported yet.");
    }

}
