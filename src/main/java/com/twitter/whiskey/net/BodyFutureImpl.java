/*
 * Copyright (c) 2015 Twitter, Inc. All rights reserved.
 * Licensed under the Apache License v2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.twitter.whiskey.net;

import com.twitter.whiskey.futures.ReactiveFuture;
import com.twitter.whiskey.util.Platform;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * Internal implementation of BodyFuture.
 *
 * @author Michael Schore
 */
class BodyFutureImpl extends ReactiveFuture<ByteBuffer, ByteBuffer> implements BodyFuture {

    private ByteBuffer body;
    private LinkedList<Integer> boundaries = new LinkedList<>();
    private int expectedLength = 0;

    void setExpectedLength(int expectedLength) {
        this.expectedLength = expectedLength;
    }

    @Override
    public void accumulate(ByteBuffer element) {

        if (!element.hasRemaining()) return;
        if (body == null) {
            body = ByteBuffer.allocate(Math.max(expectedLength, element.remaining()));
            Platform.LOGGER.debug("allocated " + body.capacity());
        }

        if (body.remaining() < element.remaining()) {
            int required = body.position() + element.remaining();
            // Allocate nearest power of 2 higher than the total required space
            assert(required < Integer.MAX_VALUE >> 1);
            ByteBuffer expanded = ByteBuffer.allocate(Integer.highestOneBit(required) << 1);
            body.flip();
            expanded.put(body);
            expanded.put(element);
            body = expanded;
            Platform.LOGGER.debug("grew buffer to " + body.capacity());
        } else {
            body.put(element);
        }
        boundaries.add(body.position());
    }

    @Override
    public Iterable<ByteBuffer> drain() {
        List<ByteBuffer> chunks = new ArrayList<>(boundaries.size());
        if (body != null) {
            body.flip();
            for (int limit : boundaries) {
                body.limit(limit);
                chunks.add(body.slice().asReadOnlyBuffer());
                body.position(limit);
            }
            body = null;
        }
        return chunks;
    }

    @Override
    public boolean complete() {
        if (body != null) body.flip();
        return set(body);
    }
}
