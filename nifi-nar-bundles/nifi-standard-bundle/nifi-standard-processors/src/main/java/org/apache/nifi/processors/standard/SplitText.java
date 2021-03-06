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
package org.apache.nifi.processors.standard;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.nifi.annotation.behavior.EventDriven;
import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.behavior.InputRequirement.Requirement;
import org.apache.nifi.annotation.behavior.SideEffectFree;
import org.apache.nifi.annotation.behavior.SupportsBatching;
import org.apache.nifi.annotation.behavior.WritesAttribute;
import org.apache.nifi.annotation.behavior.WritesAttributes;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.SeeAlso;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.flowfile.attributes.CoreAttributes;
import org.apache.nifi.logging.ProcessorLog;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.ProcessorInitializationContext;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.io.InputStreamCallback;
import org.apache.nifi.processor.io.OutputStreamCallback;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.stream.io.BufferedInputStream;
import org.apache.nifi.stream.io.BufferedOutputStream;
import org.apache.nifi.stream.io.ByteArrayOutputStream;
import org.apache.nifi.stream.io.ByteCountingInputStream;
import org.apache.nifi.util.IntegerHolder;
import org.apache.nifi.util.ObjectHolder;

@EventDriven
@SideEffectFree
@SupportsBatching
@Tags({"split", "text"})
@InputRequirement(Requirement.INPUT_REQUIRED)
@CapabilityDescription("Splits a text file into multiple smaller text files on line boundaries, each having up to a configured number of lines")
@WritesAttributes({
    @WritesAttribute(attribute = "text.line.count", description = "The number of lines of text from the original FlowFile that were copied to this FlowFile"),
    @WritesAttribute(attribute = "fragment.identifier", description = "All split FlowFiles produced from the same parent FlowFile will have the same randomly generated UUID added for this attribute"),
    @WritesAttribute(attribute = "fragment.index", description = "A one-up number that indicates the ordering of the split FlowFiles that were created from a single parent FlowFile"),
    @WritesAttribute(attribute = "fragment.count", description = "The number of split FlowFiles generated from the parent FlowFile"),
    @WritesAttribute(attribute = "segment.original.filename ", description = "The filename of the parent FlowFile")})
@SeeAlso(MergeContent.class)
public class SplitText extends AbstractProcessor {

    // attribute keys
    public static final String SPLIT_LINE_COUNT = "text.line.count";
    public static final String FRAGMENT_ID = "fragment.identifier";
    public static final String FRAGMENT_INDEX = "fragment.index";
    public static final String FRAGMENT_COUNT = "fragment.count";
    public static final String SEGMENT_ORIGINAL_FILENAME = "segment.original.filename";

    public static final PropertyDescriptor LINE_SPLIT_COUNT = new PropertyDescriptor.Builder()
            .name("Line Split Count")
            .description("The number of lines that will be added to each split file")
            .required(true)
            .addValidator(StandardValidators.POSITIVE_INTEGER_VALIDATOR)
            .build();
    public static final PropertyDescriptor HEADER_LINE_COUNT = new PropertyDescriptor.Builder()
            .name("Header Line Count")
            .description("The number of lines that should be considered part of the header; the header lines will be duplicated to all split files")
            .required(true)
            .addValidator(StandardValidators.NON_NEGATIVE_INTEGER_VALIDATOR)
            .defaultValue("0")
            .build();
    public static final PropertyDescriptor REMOVE_TRAILING_NEWLINES = new PropertyDescriptor.Builder()
            .name("Remove Trailing Newlines")
            .description("Whether to remove newlines at the end of each split file. This should be false if you intend to merge the split files later")
            .required(true)
            .addValidator(StandardValidators.BOOLEAN_VALIDATOR)
            .allowableValues("true", "false")
            .defaultValue("true")
            .build();

    public static final Relationship REL_ORIGINAL = new Relationship.Builder()
            .name("original")
            .description("The original input file will be routed to this destination when it has been successfully split into 1 or more files")
            .build();
    public static final Relationship REL_SPLITS = new Relationship.Builder()
            .name("splits")
            .description("The split files will be routed to this destination when an input file is successfully split into 1 or more split files")
            .build();
    public static final Relationship REL_FAILURE = new Relationship.Builder()
            .name("failure")
            .description("If a file cannot be split for some reason, the original file will be routed to this destination and nothing will be routed elsewhere")
            .build();

    private List<PropertyDescriptor> properties;
    private Set<Relationship> relationships;

    @Override
    protected void init(final ProcessorInitializationContext context) {
        final List<PropertyDescriptor> properties = new ArrayList<>();
        properties.add(LINE_SPLIT_COUNT);
        properties.add(HEADER_LINE_COUNT);
        properties.add(REMOVE_TRAILING_NEWLINES);
        this.properties = Collections.unmodifiableList(properties);

        final Set<Relationship> relationships = new HashSet<>();
        relationships.add(REL_ORIGINAL);
        relationships.add(REL_SPLITS);
        relationships.add(REL_FAILURE);
        this.relationships = Collections.unmodifiableSet(relationships);
    }

    @Override
    public Set<Relationship> getRelationships() {
        return relationships;
    }

    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return properties;
    }

    private int readLines(final InputStream in, final int maxNumLines, final OutputStream out, final boolean keepAllNewLines) throws IOException {
        int numLines = 0;
        for (int i = 0; i < maxNumLines; i++) {
            final long bytes = countBytesToSplitPoint(in, out, keepAllNewLines || (i != maxNumLines - 1));
            if (bytes <= 0) {
                return numLines;
            }

            numLines++;
        }

        return numLines;
    }

    private long countBytesToSplitPoint(final InputStream in, final OutputStream out, final boolean includeLineDelimiter) throws IOException {
        int lastByte = -1;
        long bytesRead = 0L;

        while (true) {
            in.mark(1);
            final int nextByte = in.read();

            // if we hit end of stream or new line we're done
            if (nextByte == -1) {
                if (lastByte == '\r') {
                    return includeLineDelimiter ? bytesRead : bytesRead - 1;
                } else {
                    return bytesRead;
                }
            }

            // if there's an OutputStream to copy the data to, copy it, if appropriate.
            // "if appropriate" means that it's not a line delimiter or that we want to copy line delimiters
            bytesRead++;
            if (out != null && (includeLineDelimiter || (nextByte != '\n' && nextByte != '\r'))) {
                out.write(nextByte);
            }

            // if we have a new line, then we're done
            if (nextByte == '\n') {
                if (includeLineDelimiter) {
                    return bytesRead;
                } else {
                    return (lastByte == '\r') ? bytesRead - 2 : bytesRead - 1;
                }
            }

            // we didn't get a new line but if last byte was carriage return we've reached a new-line.
            // so we roll back the last byte that we read and return
            if (lastByte == '\r') {
                in.reset();
                bytesRead--;    // we reset the stream by 1 byte so decrement the number of bytes read by 1
                return includeLineDelimiter ? bytesRead : bytesRead - 1;
            }

            // keep track of what the last byte was that we read so that we can detect \r followed by some other
            // character.
            lastByte = nextByte;
        }
    }

    private SplitInfo countBytesToSplitPoint(final InputStream in, final int numLines, final boolean keepAllNewLines) throws IOException {
        SplitInfo info = new SplitInfo();

        while (info.lengthLines < numLines) {
            final long bytesTillNext = countBytesToSplitPoint(in, null, keepAllNewLines || (info.lengthLines != numLines - 1));
            if (bytesTillNext <= 0L) {
                break;
            }

            info.lengthLines++;
            info.lengthBytes += bytesTillNext;
        }

        return info;
    }

    @Override
    public void onTrigger(final ProcessContext context, final ProcessSession session) {
        final FlowFile flowFile = session.get();
        if (flowFile == null) {
            return;
        }

        final ProcessorLog logger = getLogger();
        final int headerCount = context.getProperty(HEADER_LINE_COUNT).asInteger();
        final int splitCount = context.getProperty(LINE_SPLIT_COUNT).asInteger();
        final boolean removeTrailingNewlines = context.getProperty(REMOVE_TRAILING_NEWLINES).asBoolean();

        final ObjectHolder<String> errorMessage = new ObjectHolder<>(null);
        final ArrayList<SplitInfo> splitInfos = new ArrayList<>();

        final long startNanos = System.nanoTime();
        final List<FlowFile> splits = new ArrayList<>();
        session.read(flowFile, new InputStreamCallback() {
            @Override
            public void process(final InputStream rawIn) throws IOException {
                try (final BufferedInputStream bufferedIn = new BufferedInputStream(rawIn);
                        final ByteCountingInputStream in = new ByteCountingInputStream(bufferedIn)) {

                    // if we have header lines, copy them into a ByteArrayOutputStream
                    final ByteArrayOutputStream headerStream = new ByteArrayOutputStream();
                    final int headerLinesCopied = readLines(in, headerCount, headerStream, true);
                    if (headerLinesCopied < headerCount) {
                        errorMessage.set("Header Line Count is set to " + headerCount + " but file had only " + headerLinesCopied + " lines");
                        return;
                    }

                    while (true) {
                        if (headerCount > 0) {
                            // if we have header lines, create a new FlowFile, copy the header lines to that file,
                            // and then start copying lines
                            final IntegerHolder linesCopied = new IntegerHolder(0);
                            FlowFile splitFile = session.create(flowFile);
                            try {
                                splitFile = session.write(splitFile, new OutputStreamCallback() {
                                    @Override
                                    public void process(final OutputStream rawOut) throws IOException {
                                        try (final BufferedOutputStream out = new BufferedOutputStream(rawOut)) {
                                            headerStream.writeTo(out);
                                            linesCopied.set(readLines(in, splitCount, out, !removeTrailingNewlines));
                                        }
                                    }
                                });
                                splitFile = session.putAttribute(splitFile, SPLIT_LINE_COUNT, String.valueOf(linesCopied.get()));
                                logger.debug("Created Split File {} with {} lines", new Object[]{splitFile, linesCopied.get()});
                            } finally {
                                if (linesCopied.get() > 0) {
                                    splits.add(splitFile);
                                } else {
                                    // if the number of content lines is a multiple of the SPLIT_LINE_COUNT,
                                    // the last flow file will contain just a header; don't forward that one
                                    session.remove(splitFile);
                                }
                            }

                            // If we copied fewer lines than what we want, then we're done copying data (we've hit EOF).
                            if (linesCopied.get() < splitCount) {
                                break;
                            }
                        } else {
                            // We have no header lines, so we can simply demarcate the original File via the
                            // ProcessSession#clone method.
                            long beforeReadingLines = in.getBytesConsumed();
                            final SplitInfo info = countBytesToSplitPoint(in, splitCount, !removeTrailingNewlines);
                            if (info.lengthBytes == 0) {
                                // stream is out of data
                                break;
                            } else {
                                info.offsetBytes = beforeReadingLines;
                                splitInfos.add(info);
                                final long procNanos = System.nanoTime() - startNanos;
                                final long procMillis = TimeUnit.MILLISECONDS.convert(procNanos, TimeUnit.NANOSECONDS);
                                logger.debug("Detected start of Split File in {} at byte offset {} with a length of {} bytes; "
                                        + "total splits = {}; total processing time = {} ms",
                                        new Object[]{flowFile, beforeReadingLines, info.lengthBytes, splitInfos.size(), procMillis});
                            }
                        }
                    }
                }
            }
        });

        if (errorMessage.get() != null) {
            logger.error("Unable to split {} due to {}; routing to failure", new Object[]{flowFile, errorMessage.get()});
            session.transfer(flowFile, REL_FAILURE);
            if (splits != null && !splits.isEmpty()) {
                session.remove(splits);
            }
            return;
        }

        if (!splitInfos.isEmpty()) {
            // Create the splits
            for (final SplitInfo info : splitInfos) {
                FlowFile split = session.clone(flowFile, info.offsetBytes, info.lengthBytes);
                split = session.putAttribute(split, SPLIT_LINE_COUNT, String.valueOf(info.lengthLines));
                splits.add(split);
            }
        }
        finishFragmentAttributes(session, flowFile, splits);

        if (splits.size() > 10) {
            logger.info("Split {} into {} files", new Object[]{flowFile, splits.size()});
        } else {
            logger.info("Split {} into {} files: {}", new Object[]{flowFile, splits.size(), splits});
        }

        session.transfer(flowFile, REL_ORIGINAL);
        session.transfer(splits, REL_SPLITS);
    }

    private void finishFragmentAttributes(final ProcessSession session, final FlowFile source, final List<FlowFile> splits) {
        final String originalFilename = source.getAttribute(CoreAttributes.FILENAME.key());

        final String fragmentId = UUID.randomUUID().toString();
        final ArrayList<FlowFile> newList = new ArrayList<>(splits);
        splits.clear();
        for (int i = 1; i <= newList.size(); i++) {
            FlowFile ff = newList.get(i - 1);
            final Map<String, String> attributes = new HashMap<>();
            attributes.put(FRAGMENT_ID, fragmentId);
            attributes.put(FRAGMENT_INDEX, String.valueOf(i));
            attributes.put(FRAGMENT_COUNT, String.valueOf(newList.size()));
            attributes.put(SEGMENT_ORIGINAL_FILENAME, originalFilename);
            FlowFile newFF = session.putAllAttributes(ff, attributes);
            splits.add(newFF);
        }
    }

    private class SplitInfo {

        public long offsetBytes;
        public long lengthBytes;
        public long lengthLines;

        public SplitInfo() {
            super();
            this.offsetBytes = 0L;
            this.lengthBytes = 0L;
            this.lengthLines = 0L;
        }

        @SuppressWarnings("unused")
        public SplitInfo(long offsetBytes, long lengthBytes, long lengthLines) {
            super();
            this.offsetBytes = offsetBytes;
            this.lengthBytes = lengthBytes;
            this.lengthLines = lengthLines;
        }
    }
}
