/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jclouds.azureblob.blobstore;

import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.net.URI;
import java.util.Date;
import java.util.Set;
import java.util.regex.Pattern;

import org.jclouds.azure.storage.domain.BoundedSet;
import org.jclouds.azure.storage.domain.internal.BoundedHashSet;
import org.jclouds.azure.storage.options.ListOptions;
import org.jclouds.azureblob.AzureBlobClient;
import org.jclouds.azureblob.blobstore.functions.AzureBlobToBlob;
import org.jclouds.azureblob.blobstore.functions.BlobPropertiesToBlobMetadata;
import org.jclouds.azureblob.blobstore.functions.BlobToAzureBlob;
import org.jclouds.azureblob.blobstore.functions.ContainerToResourceMetadata;
import org.jclouds.azureblob.blobstore.functions.ListBlobsResponseToResourceList;
import org.jclouds.azureblob.blobstore.functions.ListOptionsToListBlobsOptions;
import org.jclouds.azureblob.domain.ContainerProperties;
import org.jclouds.azureblob.domain.PublicAccess;
import org.jclouds.azureblob.domain.internal.ContainerPropertiesImpl;
import org.jclouds.blobstore.BlobStoreContext;
import org.jclouds.blobstore.domain.MutableStorageMetadata;
import org.jclouds.blobstore.domain.PageSet;
import org.jclouds.blobstore.domain.StorageMetadata;
import org.jclouds.blobstore.domain.StorageType;
import org.jclouds.blobstore.domain.internal.MutableStorageMetadataImpl;
import org.jclouds.blobstore.functions.BlobToHttpGetOptions;
import org.jclouds.blobstore.util.BlobUtils;
import org.jclouds.domain.Location;
import org.jclouds.io.PayloadSlicer;
import org.testng.annotations.Test;

import com.google.common.base.Function;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

/**
 * Tests behavior of {@code AzureBlobStore}
 */
// NOTE:without testName, this will not call @Before* and fail w/NPE during surefire
@Test(groups = "unit", testName = "AzureBlobStore")
public class AzureBlobStoreTest {

   private static final Pattern VALIDATION_PATTERN = Pattern.compile("^[a-zA-Z0-9+/=]*$");

   public void testMakeBlockId() {
      checkBlockIdForPartNumber(0);
      checkBlockIdForPartNumber(1);
      checkBlockIdForPartNumber(248);
      checkBlockIdForPartNumber(504);
      checkBlockIdForPartNumber(760);
      checkBlockIdForPartNumber(1016);
      checkBlockIdForPartNumber(1272);
      checkBlockIdForPartNumber(4600);
      checkBlockIdForPartNumber(6654);
      checkBlockIdForPartNumber(867840);
      checkBlockIdForPartNumber(868091);
      checkBlockIdForPartNumber(868096);
      checkBlockIdForPartNumber(-1);
      checkBlockIdForPartNumber(-1023);
   }

   /**
    * Verifies that list() follows nextMarker pagination and returns all containers,
    * not just the first page. Regression test for the silent truncation at the
    * Azure-imposed page limit.
    */
   public void testListFollowsPaginationAcrossAllPages() {
      ContainerProperties c1 = makeContainer("account", "alpha");
      ContainerProperties c2 = makeContainer("account", "beta");
      ContainerProperties c3 = makeContainer("account", "gamma");
      ContainerProperties c4 = makeContainer("account", "delta");

      URI base = URI.create("https://account.blob.core.windows.net/");
      BoundedSet<ContainerProperties> page1 =
            new BoundedHashSet<>(ImmutableList.of(c1, c2), base, null, null, 2, "page2marker");
      BoundedSet<ContainerProperties> page2 =
            new BoundedHashSet<>(ImmutableList.of(c3, c4), base, null, null, 2, null);

      AzureBlobClient mockClient = createMock(AzureBlobClient.class);
      expect(mockClient.listContainers(anyObject(ListOptions.class))).andReturn(page1).once();
      expect(mockClient.listContainers(anyObject(ListOptions.class))).andReturn(page2).once();
      replay(mockClient);

      AzureBlobStore store = makeStore(mockClient);
      PageSet<? extends StorageMetadata> result = store.list();

      assertEquals(result.size(), 4, "list() must aggregate all pages, not stop after the first");
      assertNull(result.getNextMarker(), "nextMarker must be null when all pages have been consumed");

      verify(mockClient);
   }

   // helpers

   private void checkBlockIdForPartNumber(int partNumber) {
      String blockId = AzureBlobStore.makeBlockId(partNumber);
      assertTrue(VALIDATION_PATTERN.matcher(blockId).find());
   }

   private static ContainerProperties makeContainer(String account, String name) {
      return new ContainerPropertiesImpl(
            URI.create("https://" + account + ".blob.core.windows.net/" + name),
            new Date(), "etag-" + name, ImmutableMap.<String, String>of(), PublicAccess.PRIVATE);
   }

   private static AzureBlobStore makeStore(AzureBlobClient client) {
      Supplier<Location> noLocation = new Supplier<Location>() {
         public Location get() { return null; }
      };
      Supplier<Set<? extends Location>> noLocations = new Supplier<Set<? extends Location>>() {
         public Set<? extends Location> get() { return ImmutableList.<Location>of().stream().collect(
               java.util.stream.Collectors.toSet()); }
      };
      // ContainerToResourceMetadata constructor is package-private; use a simple inline Function instead
      ContainerToResourceMetadata container2Md = createMock(ContainerToResourceMetadata.class);
      expect(container2Md.apply(anyObject(ContainerProperties.class))).andAnswer(() -> {
         ContainerProperties cp = (ContainerProperties) org.easymock.EasyMock.getCurrentArguments()[0];
         MutableStorageMetadata md = new MutableStorageMetadataImpl();
         md.setName(cp.getName());
         md.setType(StorageType.CONTAINER);
         return md;
      }).anyTimes();
      replay(container2Md);

      return new AzureBlobStore(
            createMock(BlobStoreContext.class),
            createMock(BlobUtils.class),
            noLocation,
            noLocations,
            createMock(PayloadSlicer.class),
            client,
            container2Md,
            createMock(ListOptionsToListBlobsOptions.class),
            createMock(ListBlobsResponseToResourceList.class),
            createMock(AzureBlobToBlob.class),
            createMock(BlobToAzureBlob.class),
            createMock(BlobPropertiesToBlobMetadata.class),
            createMock(BlobToHttpGetOptions.class));
   }
}
