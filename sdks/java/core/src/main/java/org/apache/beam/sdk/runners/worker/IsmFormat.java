/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.sdk.runners.worker;

import static org.apache.beam.sdk.util.Structs.addLong;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import org.apache.beam.sdk.coders.AtomicCoder;
import org.apache.beam.sdk.coders.ByteArrayCoder;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.coders.Coder.NonDeterministicException;
import org.apache.beam.sdk.coders.CoderException;
import org.apache.beam.sdk.coders.ListCoder;
import org.apache.beam.sdk.coders.StandardCoder;
import org.apache.beam.sdk.coders.VarIntCoder;
import org.apache.beam.sdk.coders.VarLongCoder;
import org.apache.beam.sdk.util.CloudObject;
import org.apache.beam.sdk.util.PropertyNames;
import org.apache.beam.sdk.util.RandomAccessData;
import org.apache.beam.sdk.util.VarInt;
import org.apache.beam.sdk.values.PCollection;

import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.annotation.Nullable;

/**
 * An Ism file is a prefix encoded composite key value file broken into shards. Each composite
 * key is composed of a fixed number of component keys. A fixed number of those sub keys represent
 * the shard key portion; see {@link IsmRecord} and {@link IsmRecordCoder} for further details
 * around the data format. In addition to the data, there is a bloom filter,
 * and multiple indices to allow for efficient retrieval.
 *
 * <p>An Ism file is composed of these high level sections (in order):
 * <ul>
 *   <li>shard block</li>
 *   <li>bloom filter (See {@code ScalableBloomFilter} for details on encoding format)</li>
 *   <li>shard index</li>
 *   <li>footer (See {@link Footer} for details on encoding format)</li>
 * </ul>
 *
 * <p>The shard block is composed of multiple copies of the following:
 * <ul>
 *   <li>data block</li>
 *   <li>data index</li>
 * </ul>
 *
 * <p>The data block is composed of multiple copies of the following:
 * <ul>
 *   <li>key prefix (See {@link KeyPrefix} for details on encoding format)</li>
 *   <li>unshared key bytes</li>
 *   <li>value bytes</li>
 *   <li>optional 0x00 0x00 bytes followed by metadata bytes
 *       (if the following 0x00 0x00 bytes are not present, then there are no metadata bytes)</li>
 * </ul>
 * Each key written into the data block must be in unsigned lexicographically increasing order
 * and also its shard portion of the key must hash to the same shard id as all other keys
 * within the same data block. The hashing function used is the
 * <a href="http://smhasher.googlecode.com/svn/trunk/MurmurHash3.cpp">
 * 32-bit murmur3 algorithm, x86 variant</a> (little-endian variant),
 * using {@code 1225801234} as the seed value.
 *
 * <p>The data index is composed of {@code N} copies of the following:
 * <ul>
 *   <li>key prefix (See {@link KeyPrefix} for details on encoding format)</li>
 *   <li>unshared key bytes</li>
 *   <li>byte offset to key prefix in data block (variable length long coding)</li>
 * </ul>
 *
 * <p>The shard index is composed of a {@link VarInt variable length integer} encoding representing
 * the number of shard index records followed by that many shard index records.
 * See {@link IsmShardCoder} for further details as to its encoding scheme.
 */
public class IsmFormat {
  private static final int HASH_SEED = 1225801234;
  private static final HashFunction HASH_FUNCTION = Hashing.murmur3_32(HASH_SEED);
  public static final int SHARD_BITS = 0x7F; // [0-127] shards + [128-255] metadata shards

  /**
   * A record containing a composite key and either a value or metadata. The composite key
   * must not contain the metadata key component place holder if producing a value record, and must
   * contain the metadata component key place holder if producing a metadata record.
   *
   * <p>The composite key is a fixed number of component keys where the first {@code N} component
   * keys are used to create a shard id via hashing. See {@link IsmRecordCoder#hash(List)} for
   * further details.
   */
  public static class IsmRecord<V> {
    /** Returns an IsmRecord with the specified key components and value. */
    public static <V> IsmRecord<V> of(List<?> keyComponents, V value) {
      checkNotNull(keyComponents);
      checkArgument(!keyComponents.isEmpty(), "Expected non-empty list of key components.");
      checkArgument(!isMetadataKey(keyComponents),
          "Expected key components to not contain metadata key.");
      return new IsmRecord<>(keyComponents, value, null);
    }

    public static <V> IsmRecord<V> meta(List<?> keyComponents, byte[] metadata) {
      checkNotNull(keyComponents);
      checkNotNull(metadata);
      checkArgument(!keyComponents.isEmpty(), "Expected non-empty list of key components.");
      checkArgument(isMetadataKey(keyComponents),
          "Expected key components to contain metadata key.");
      return new IsmRecord<V>(keyComponents, null, metadata);
    }

    private final List<?> keyComponents;
    @Nullable
    private final V value;
    @Nullable
    private final byte[] metadata;
    private IsmRecord(List<?> keyComponents, V value, byte[] metadata) {
      this.keyComponents = keyComponents;
      this.value = value;
      this.metadata = metadata;
    }

    /** Returns the list of key components. */
    public List<?> getKeyComponents() {
      return keyComponents;
    }

    /** Returns the key component at the specified index. */
    public Object getKeyComponent(int index) {
      return keyComponents.get(index);
    }

    /**
     * Returns the value. Throws {@link IllegalStateException} if this is not a
     * value record.
     */
    public V getValue() {
      checkState(!isMetadataKey(keyComponents),
          "This is a metadata record and not a value record.");
      return value;
    }

    /**
     * Returns the metadata. Throws {@link IllegalStateException} if this is not a
     * metadata record.
     */
    public byte[] getMetadata() {
      checkState(isMetadataKey(keyComponents),
          "This is a value record and not a metadata record.");
      return metadata;
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof IsmRecord)) {
        return false;
      }
      IsmRecord<?> other = (IsmRecord<?>) obj;
      return Objects.equal(keyComponents, other.keyComponents)
          && Objects.equal(value, other.value)
          && Arrays.equals(metadata, other.metadata);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(keyComponents, value, Arrays.hashCode(metadata));
    }

    @Override
    public String toString() {
      ToStringHelper builder = MoreObjects.toStringHelper(IsmRecord.class)
          .add("keyComponents", keyComponents);
      if (isMetadataKey(keyComponents)) {
        builder.add("metadata", metadata);
      } else {
        builder.add("value", value);
      }
      return builder.toString();
    }
  }

  /** A {@link Coder} for {@link IsmRecord}s.
   *
   * <p>Note that this coder standalone will not produce an Ism file. This coder can be used
   * to materialize a {@link PCollection} of {@link IsmRecord}s. Only when this coder
   * is combined with an {@link IsmSink} will one produce an Ism file.
   *
   * <p>The {@link IsmRecord} encoded format is:
   * <ul>
   *   <li>encoded key component 1 using key component coder 1</li>
   *   <li>...</li>
   *   <li>encoded key component N using key component coder N</li>
   *   <li>encoded value using value coder</li>
   * </ul>
   */
  public static class IsmRecordCoder<V>
      extends StandardCoder<IsmRecord<V>> {
    /** Returns an IsmRecordCoder with the specified key component coders, value coder. */
    public static <V> IsmRecordCoder<V> of(
        int numberOfShardKeyCoders,
        int numberOfMetadataShardKeyCoders,
        List<Coder<?>> keyComponentCoders,
        Coder<V> valueCoder) {
      checkNotNull(keyComponentCoders);
      checkArgument(keyComponentCoders.size() > 0);
      checkArgument(numberOfShardKeyCoders > 0);
      checkArgument(numberOfShardKeyCoders <= keyComponentCoders.size());
      checkArgument(numberOfMetadataShardKeyCoders <= keyComponentCoders.size());
      return new IsmRecordCoder<>(
          numberOfShardKeyCoders,
          numberOfMetadataShardKeyCoders,
          keyComponentCoders,
          valueCoder);
    }

    /**
     * Returns an IsmRecordCoder with the specified coders. Note that this method is not meant
     * to be called by users but used by Jackson when decoding this coder.
     */
    @JsonCreator
    public static IsmRecordCoder<?> of(
        @JsonProperty(PropertyNames.NUM_SHARD_CODERS) int numberOfShardCoders,
        @JsonProperty(PropertyNames.NUM_METADATA_SHARD_CODERS) int numberOfMetadataShardCoders,
        @JsonProperty(PropertyNames.COMPONENT_ENCODINGS) List<Coder<?>> components) {
      Preconditions.checkArgument(components.size() >= 2,
          "Expecting at least 2 components, got " + components.size());
      return of(
          numberOfShardCoders,
          numberOfMetadataShardCoders,
          components.subList(0, components.size() - 1),
          components.get(components.size() - 1));
    }

    private final int numberOfShardKeyCoders;
    private final int numberOfMetadataShardKeyCoders;
    private final List<Coder<?>> keyComponentCoders;
    private final Coder<V> valueCoder;

    private IsmRecordCoder(
        int numberOfShardKeyCoders,
        int numberOfMetadataShardKeyCoders,
        List<Coder<?>> keyComponentCoders, Coder<V> valueCoder) {
      this.numberOfShardKeyCoders = numberOfShardKeyCoders;
      this.numberOfMetadataShardKeyCoders = numberOfMetadataShardKeyCoders;
      this.keyComponentCoders = keyComponentCoders;
      this.valueCoder = valueCoder;
    }

    /** Returns the list of key component coders. */
    public List<Coder<?>> getKeyComponentCoders() {
      return keyComponentCoders;
    }

    /** Returns the key coder at the specified index. */
    public Coder getKeyComponentCoder(int index) {
      return keyComponentCoders.get(index);
    }

    /** Returns the value coder. */
    public Coder<V> getValueCoder() {
      return valueCoder;
    }

    @Override
    public void encode(IsmRecord<V> value, OutputStream outStream,
        Coder.Context context) throws CoderException, IOException {
      if (value.getKeyComponents().size() != keyComponentCoders.size()) {
        throw new CoderException(String.format(
            "Expected %s key component(s) but received key component(s) %s.",
            keyComponentCoders.size(), value.getKeyComponents()));
      }
      for (int i = 0; i < keyComponentCoders.size(); ++i) {
        getKeyComponentCoder(i).encode(value.getKeyComponent(i), outStream, context.nested());
      }
      if (isMetadataKey(value.getKeyComponents())) {
        ByteArrayCoder.of().encode(value.getMetadata(), outStream, context.nested());
      } else {
        valueCoder.encode(value.getValue(), outStream, context.nested());
      }
    }

    @Override
    public IsmRecord<V> decode(InputStream inStream, Coder.Context context)
        throws CoderException, IOException {
      List<Object> keyComponents = new ArrayList<>(keyComponentCoders.size());
      for (Coder<?> keyCoder : keyComponentCoders) {
        keyComponents.add(keyCoder.decode(inStream, context.nested()));
      }
      if (isMetadataKey(keyComponents)) {
        return IsmRecord.<V>meta(
            keyComponents, ByteArrayCoder.of().decode(inStream, context.nested()));
      } else {
        return IsmRecord.<V>of(keyComponents, valueCoder.decode(inStream, context.nested()));
      }
    }

    public int getNumberOfShardKeyCoders(List<?> keyComponents) {
      if (isMetadataKey(keyComponents)) {
        return numberOfMetadataShardKeyCoders;
      } else {
        return numberOfShardKeyCoders;
      }
    }

    /**
     * Computes the shard id for the given key component(s).
     *
     * The shard keys are encoded into their byte representations and hashed using the
     * <a href="http://smhasher.googlecode.com/svn/trunk/MurmurHash3.cpp">
     * 32-bit murmur3 algorithm, x86 variant</a> (little-endian variant),
     * using {@code 1225801234} as the seed value. We ensure that shard ids for
     * metadata keys and normal keys do not overlap.
     */
    public <V, T> int hash(List<?> keyComponents) {
      return encodeAndHash(keyComponents, new RandomAccessData(), new ArrayList<Integer>());
    }

    /**
     * Computes the shard id for the given key component(s).
     *
     * Mutates {@code keyBytes} such that when returned, contains the encoded
     * version of the key components.
     */
    public <V, T> int encodeAndHash(List<?> keyComponents, RandomAccessData keyBytesToMutate) {
      return encodeAndHash(keyComponents, keyBytesToMutate, new ArrayList<Integer>());
    }

    /**
     * Computes the shard id for the given key component(s).
     *
     * Mutates {@code keyBytes} such that when returned, contains the encoded
     * version of the key components. Also, mutates {@code keyComponentByteOffsetsToMutate} to
     * store the location where each key component's encoded byte representation ends within
     * {@code keyBytes}.
     */
    public <V, T> int encodeAndHash(
        List<?> keyComponents,
        RandomAccessData keyBytesToMutate,
        List<Integer> keyComponentByteOffsetsToMutate) {
      checkNotNull(keyComponents);
      checkArgument(keyComponents.size() <= keyComponentCoders.size(),
          "Expected at most %s key component(s) but received %s.",
          keyComponentCoders.size(), keyComponents);

      final int numberOfKeyCodersToUse;
      final int shardOffset;
      if (isMetadataKey(keyComponents)) {
        numberOfKeyCodersToUse = numberOfMetadataShardKeyCoders;
        shardOffset = SHARD_BITS + 1;
      } else {
        numberOfKeyCodersToUse = numberOfShardKeyCoders;
        shardOffset = 0;
      }

      checkArgument(numberOfKeyCodersToUse <= keyComponents.size(),
          "Expected at least %s key component(s) but received %s.",
          numberOfShardKeyCoders, keyComponents);

      try {
        // Encode the shard portion
        for (int i = 0; i < numberOfKeyCodersToUse; ++i) {
          getKeyComponentCoder(i).encode(
              keyComponents.get(i), keyBytesToMutate.asOutputStream(), Context.NESTED);
          keyComponentByteOffsetsToMutate.add(keyBytesToMutate.size());
        }
        int rval = HASH_FUNCTION.hashBytes(
            keyBytesToMutate.array(), 0, keyBytesToMutate.size()).asInt() & SHARD_BITS;
        rval += shardOffset;

        // Encode the remainder
        for (int i = numberOfKeyCodersToUse; i < keyComponents.size(); ++i) {
          getKeyComponentCoder(i).encode(
              keyComponents.get(i), keyBytesToMutate.asOutputStream(), Context.NESTED);
          keyComponentByteOffsetsToMutate.add(keyBytesToMutate.size());
        }
        return rval;
      } catch (IOException e) {
        throw new IllegalStateException(
            String.format("Failed to hash %s with coder %s", keyComponents, this), e);
      }
    }

    @Override
    public List<Coder<?>> getCoderArguments() {
      return ImmutableList.<Coder<?>>builder()
          .addAll(keyComponentCoders)
          .add(valueCoder)
          .build();
    }

    @Override
    public CloudObject asCloudObject() {
      CloudObject cloudObject = super.asCloudObject();
      addLong(cloudObject, PropertyNames.NUM_SHARD_CODERS, numberOfShardKeyCoders);
      addLong(cloudObject, PropertyNames.NUM_METADATA_SHARD_CODERS, numberOfMetadataShardKeyCoders);
      return cloudObject;
    }

    @Override
    public void verifyDeterministic() throws Coder.NonDeterministicException {
      verifyDeterministic("Key component coders expected to be deterministic.", keyComponentCoders);
      verifyDeterministic("Value coder expected to be deterministic.", valueCoder);
    }

    @Override
    public boolean consistentWithEquals() {
      for (Coder<?> keyComponentCoder : keyComponentCoders) {
        if (!keyComponentCoder.consistentWithEquals()) {
          return false;
        }
      }
      return valueCoder.consistentWithEquals();
    }

    @Override
    public Object structuralValue(IsmRecord<V> record) throws Exception {
      checkState(record.getKeyComponents().size() == keyComponentCoders.size(),
          "Expected the number of key component coders %s "
          + "to match the number of key components %s.",
          keyComponentCoders.size(), record.getKeyComponents());

      if (record != null && consistentWithEquals()) {
        ArrayList<Object> keyComponentStructuralValues = new ArrayList<>();
        for (int i = 0; i < keyComponentCoders.size(); ++i) {
          keyComponentStructuralValues.add(
              getKeyComponentCoder(i).structuralValue(record.getKeyComponent(i)));
        }
        if (isMetadataKey(record.getKeyComponents())) {
          return IsmRecord.meta(keyComponentStructuralValues, record.getMetadata());
        } else {
          return IsmRecord.of(keyComponentStructuralValues,
              valueCoder.structuralValue(record.getValue()));
        }
      }
      return super.structuralValue(record);
    }
  }

  /**
   * Validates that the key portion of the given coder is deterministic.
   */
  public static void validateCoderIsCompatible(IsmRecordCoder<?> coder) {
    for (Coder<?> keyComponentCoder : coder.getKeyComponentCoders()) {
      try {
          keyComponentCoder.verifyDeterministic();
      } catch (NonDeterministicException e) {
        throw new IllegalArgumentException(
            String.format("Key component coder %s is expected to be deterministic.",
                keyComponentCoder), e);
      }
    }
  }

  /** Returns true if and only if any of the passed in key components represent a metadata key. */
  public static boolean isMetadataKey(List<?> keyComponents) {
    for (Object keyComponent : keyComponents) {
      if (keyComponent == METADATA_KEY) {
        return true;
      }
    }
    return false;
  }

  /** A marker object representing the wildcard metadata key component. */
  private static final Object METADATA_KEY = new Object() {
    @Override
    public String toString() {
      return "META";
    }

    @Override
    public boolean equals(Object obj) {
      return this == obj;
    }

    @Override
    public int hashCode() {
      return -1248902349;
    }
  };

  /**
   * An object representing a wild card for a key component.
   * Encoded using {@link MetadataKeyCoder}.
   */
  public static Object getMetadataKey() {
    return METADATA_KEY;
  }

  /**
   * A coder for metadata key component. Can be used to wrap key component coder allowing for
   * the metadata key component to be used as a place holder instead of an actual key.
   */
  public static class MetadataKeyCoder<K> extends StandardCoder<K> {
    public static <K> MetadataKeyCoder<K> of(Coder<K> keyCoder) {
      checkNotNull(keyCoder);
      return new MetadataKeyCoder<>(keyCoder);
    }

    /**
     * Returns an IsmRecordCoder with the specified coders. Note that this method is not meant
     * to be called by users but used by Jackson when decoding this coder.
     */
    @JsonCreator
    public static MetadataKeyCoder<?> of(
        @JsonProperty(PropertyNames.COMPONENT_ENCODINGS) List<Coder<?>> components) {
      Preconditions.checkArgument(components.size() == 1,
          "Expecting one component, got " + components.size());
      return of(components.get(0));
    }

    private final Coder<K> keyCoder;

    private MetadataKeyCoder(Coder<K> keyCoder) {
      this.keyCoder = keyCoder;
    }

    public Coder<K> getKeyCoder() {
      return keyCoder;
    }

    @Override
    public void encode(K value, OutputStream outStream, Coder.Context context)
        throws CoderException, IOException {
      if (value == METADATA_KEY) {
        outStream.write(0);
      } else {
        outStream.write(1);
        keyCoder.encode(value, outStream, context.nested());
      }
    }

    @Override
    public K decode(InputStream inStream, Coder.Context context)
        throws CoderException, IOException {
      int marker = inStream.read();
      if (marker == 0) {
        return (K) getMetadataKey();
      } else if (marker == 1) {
        return keyCoder.decode(inStream, context.nested());
      } else {
        throw new CoderException(String.format("Expected marker but got %s.", marker));
      }
    }

    @Override
    public List<Coder<?>> getCoderArguments() {
      return ImmutableList.<Coder<?>>of(keyCoder);
    }

    @Override
    public void verifyDeterministic() throws NonDeterministicException {
      verifyDeterministic("Expected key coder to be deterministic", keyCoder);
    }
  }

  /**
   * A shard descriptor containing shard id, the data block offset, and the index offset for the
   * given shard.
   */
  public static class IsmShard {
    private final int id;
    private final long blockOffset;
    private final long indexOffset;

    /** Returns an IsmShard with the given id, block offset and no index offset. */
    public static IsmShard of(int id, long blockOffset) {
      IsmShard ismShard = new IsmShard(id, blockOffset, -1);
      checkState(id >= 0,
          "%s attempting to be written with negative shard id.",
          ismShard);
      checkState(blockOffset >= 0,
          "%s attempting to be written with negative block offset.",
          ismShard);
      return ismShard;
    }

    /** Returns an IsmShard with the given id, block offset, and index offset. */
    public static IsmShard of(int id, long blockOffset, long indexOffset) {
      IsmShard ismShard = new IsmShard(id, blockOffset, indexOffset);
      checkState(id >= 0,
          "%s attempting to be written with negative shard id.",
          ismShard);
      checkState(blockOffset >= 0,
          "%s attempting to be written with negative block offset.",
          ismShard);
      checkState(indexOffset >= 0,
          "%s attempting to be written with negative index offset.",
          ismShard);
      return ismShard;
    }

    private IsmShard(int id, long blockOffset, long indexOffset) {
      this.id = id;
      this.blockOffset = blockOffset;
      this.indexOffset = indexOffset;
    }

    /** Return the shard id. */
    public int getId() {
      return id;
    }

    /** Return the absolute position within the Ism file where the data block begins. */
    public long getBlockOffset() {
      return blockOffset;
    }

    /**
     * Return the absolute position within the Ism file where the index block begins.
     * Throws {@link IllegalStateException} if the index offset was never specified.
     */
    public long getIndexOffset() {
      checkState(indexOffset >= 0,
            "Unable to fetch index offset because it was never specified.");
      return indexOffset;
    }

    /** Returns a new IsmShard like this one with the specified index offset. */
    public IsmShard withIndexOffset(long indexOffset) {
      return of(id, blockOffset, indexOffset);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(IsmShard.class)
          .add("id", id)
          .add("blockOffset", blockOffset)
          .add("indexOffset", indexOffset)
          .toString();
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof IsmShard)) {
        return false;
      }
      IsmShard other = (IsmShard) obj;
      return Objects.equal(id, other.id)
          && Objects.equal(blockOffset, other.blockOffset)
          && Objects.equal(indexOffset, other.indexOffset);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(id, blockOffset, indexOffset);
    }
  }

  /**
   * A {@link ListCoder} wrapping a {@link IsmShardCoder} used to encode the shard index.
   * See {@link ListCoder} for its encoding specification and {@link IsmShardCoder} for its
   * encoding specification.
   */
  public static final Coder<List<IsmShard>> ISM_SHARD_INDEX_CODER =
      ListCoder.of(IsmShardCoder.of());

  /**
   * A coder for {@link IsmShard}s.
   *
   * The shard descriptor is encoded as:
   * <ul>
   *   <li>id (variable length integer encoding)</li>
   *   <li>blockOffset (variable length long encoding)</li>
   *   <li>indexOffset (variable length long encoding)</li>
   * </ul>
   */
  public static class IsmShardCoder extends AtomicCoder<IsmShard> {
    private static final IsmShardCoder INSTANCE = new IsmShardCoder();

    /** Returns an IsmShardCoder. */
    @JsonCreator
    public static IsmShardCoder of() {
      return INSTANCE;
    }

    private IsmShardCoder() {
    }

    @Override
    public void encode(IsmShard value, OutputStream outStream, Coder.Context context)
        throws CoderException, IOException {
      checkState(value.getIndexOffset() >= 0,
          "%s attempting to be written without index offset.",
          value);
      VarIntCoder.of().encode(value.getId(), outStream, context.nested());
      VarLongCoder.of().encode(value.getBlockOffset(), outStream, context.nested());
      VarLongCoder.of().encode(value.getIndexOffset(), outStream, context.nested());
    }

    @Override
    public IsmShard decode(
        InputStream inStream, Coder.Context context) throws CoderException, IOException {
      return IsmShard.of(
          VarIntCoder.of().decode(inStream, context),
          VarLongCoder.of().decode(inStream, context),
          VarLongCoder.of().decode(inStream, context));
    }

    @Override
    public boolean consistentWithEquals() {
      return true;
    }
  }

  /**
   * The prefix used before each key which contains the number of shared and unshared
   * bytes from the previous key that was read. The key prefix along with the previous key
   * and the unshared key bytes allows one to construct the current key by doing the following
   * {@code currentKey = previousKey[0 : sharedBytes] + read(unsharedBytes)}.
   *
   * <p>The key prefix is encoded as:
   * <ul>
   *   <li>number of shared key bytes (variable length integer coding)</li>
   *   <li>number of unshared key bytes (variable length integer coding)</li>
   * </ul>
   */
  public static class KeyPrefix {
    private final int sharedKeySize;
    private final int unsharedKeySize;

    public KeyPrefix(int sharedBytes, int unsharedBytes) {
      this.sharedKeySize = sharedBytes;
      this.unsharedKeySize = unsharedBytes;
    }

    public int getSharedKeySize() {
      return sharedKeySize;
    }

    public int getUnsharedKeySize() {
      return unsharedKeySize;
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(sharedKeySize, unsharedKeySize);
    }

    @Override
    public boolean equals(Object other) {
      if (other == this) {
        return true;
      }
      if (!(other instanceof KeyPrefix)) {
        return false;
      }
      KeyPrefix keyPrefix = (KeyPrefix) other;
      return sharedKeySize == keyPrefix.sharedKeySize
          && unsharedKeySize == keyPrefix.unsharedKeySize;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("sharedKeySize", sharedKeySize)
          .add("unsharedKeySize", unsharedKeySize)
          .toString();
    }
  }

  /** A {@link Coder} for {@link KeyPrefix}. */
  public static final class KeyPrefixCoder extends AtomicCoder<KeyPrefix> {
    private static final KeyPrefixCoder INSTANCE = new KeyPrefixCoder();

    @JsonCreator
    public static KeyPrefixCoder of() {
      return INSTANCE;
    }

    @Override
    public void encode(KeyPrefix value, OutputStream outStream, Coder.Context context)
        throws CoderException, IOException {
      VarInt.encode(value.sharedKeySize, outStream);
      VarInt.encode(value.unsharedKeySize, outStream);
    }

    @Override
    public KeyPrefix decode(InputStream inStream, Coder.Context context)
        throws CoderException, IOException {
      return new KeyPrefix(VarInt.decodeInt(inStream), VarInt.decodeInt(inStream));
    }

    @Override
    public boolean consistentWithEquals() {
      return true;
    }

    @Override
    public boolean isRegisterByteSizeObserverCheap(KeyPrefix value, Coder.Context context) {
      return true;
    }

    @Override
    public long getEncodedElementByteSize(KeyPrefix value, Coder.Context context)
        throws Exception {
      Preconditions.checkNotNull(value);
      return VarInt.getLength(value.sharedKeySize) + VarInt.getLength(value.unsharedKeySize);
    }
  }

  /**
   * The footer stores the relevant information required to locate the index and bloom filter.
   * It also stores a version byte and the number of keys stored.
   *
   * <p>The footer is encoded as the value containing:
   * <ul>
   *   <li>start of bloom filter offset (big endian long coding)</li>
   *   <li>start of shard index position offset (big endian long coding)</li>
   *   <li>number of keys in file (big endian long coding)</li>
   *   <li>0x01 (version key as a single byte)</li>
   * </ul>
   */
  public static class Footer {
    public static final int LONG_BYTES = 8;
    public static final int FIXED_LENGTH = 3 * LONG_BYTES + 1;
    public static final byte VERSION = 2;

    private final long indexPosition;
    private final long bloomFilterPosition;
    private final long numberOfKeys;

    public Footer(long indexPosition, long bloomFilterPosition, long numberOfKeys) {
      this.indexPosition = indexPosition;
      this.bloomFilterPosition = bloomFilterPosition;
      this.numberOfKeys = numberOfKeys;
    }

    public long getIndexPosition() {
      return indexPosition;
    }

    public long getBloomFilterPosition() {
      return bloomFilterPosition;
    }

    public long getNumberOfKeys() {
      return numberOfKeys;
    }

    @Override
    public boolean equals(Object other) {
      if (other == this) {
        return true;
      }
      if (!(other instanceof Footer)) {
        return false;
      }
      Footer footer = (Footer) other;
      return indexPosition == footer.indexPosition
          && bloomFilterPosition == footer.bloomFilterPosition
          && numberOfKeys == footer.numberOfKeys;
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(indexPosition, bloomFilterPosition, numberOfKeys);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("version", Footer.VERSION)
          .add("indexPosition", indexPosition)
          .add("bloomFilterPosition", bloomFilterPosition)
          .add("numberOfKeys", numberOfKeys)
          .toString();
    }
  }

  /** A {@link Coder} for {@link Footer}. */
  public static final class FooterCoder extends AtomicCoder<Footer> {
    private static final FooterCoder INSTANCE = new FooterCoder();

    @JsonCreator
    public static FooterCoder of() {
      return INSTANCE;
    }

    @Override
    public void encode(Footer value, OutputStream outStream, Coder.Context context)
        throws CoderException, IOException {
      DataOutputStream dataOut = new DataOutputStream(outStream);
      dataOut.writeLong(value.indexPosition);
      dataOut.writeLong(value.bloomFilterPosition);
      dataOut.writeLong(value.numberOfKeys);
      dataOut.write(Footer.VERSION);
    }

    @Override
    public Footer decode(InputStream inStream, Coder.Context context)
        throws CoderException, IOException {
      DataInputStream dataIn = new DataInputStream(inStream);
      Footer footer = new Footer(dataIn.readLong(), dataIn.readLong(), dataIn.readLong());
      int version = dataIn.read();
      if (version != Footer.VERSION) {
        throw new IOException("Unknown version " + version + ". "
            + "Only version 2 is currently supported.");
      }
      return footer;
    }

    @Override
    public boolean consistentWithEquals() {
      return true;
    }

    @Override
    public boolean isRegisterByteSizeObserverCheap(Footer value, Coder.Context context) {
      return true;
    }

    @Override
    public long getEncodedElementByteSize(Footer value, Coder.Context context)
        throws Exception {
      return Footer.FIXED_LENGTH;
    }
  }
}
