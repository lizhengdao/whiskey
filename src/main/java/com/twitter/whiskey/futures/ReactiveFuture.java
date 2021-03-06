/*
 * Copyright (c) 2015 Twitter, Inc. All rights reserved.
 * Licensed under the Apache License v2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.twitter.whiskey.futures;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * @author Michael Schore
 */
public abstract class ReactiveFuture<T, E> extends CompletableFuture<T> implements ObservableFuture<T, E> {

    private static final Object SENTINEL = new Object();
    private final ArrayList<Observer<E>> observers = new ArrayList<>(4);
    private final ArrayList<StreamingIterator> iterators = new ArrayList<>(4);
    private volatile Throwable error = null;
    private volatile boolean drained = false;
    private volatile boolean streaming = false;

    public ReactiveFuture() {
    }

    public boolean provide(final E element) throws RuntimeException {

        if (isDone()) return false;

        synchronized(this) {
            if (isDone()) return false;
            if (!streaming || (observers.isEmpty() && iterators.isEmpty())) {
                accumulate(element);
                return true;
            }

            dispatch(element);
            return true;
        }
    }

    /**
     * Called by the future's creator to begin streaming to iterators and observers.
     */
    public void release() {

        if (streaming) return;

        synchronized (this) {
            if (streaming) return;
            streaming = true;

            if (observers.isEmpty() && iterators.isEmpty()) return;
            drained = true;
            for (final E element : drain()) {
                dispatch(element);
            }
        }
    }

    /**
     * Called by the future's creator to complete the future.
     */
    public boolean finish() {

        if (drained) {
            boolean result = set(null);
            if (result) {
                for (final Observer observer : observers) {
                    observer.getExecutor().execute(new Runnable() {
                        @Override
                        public void run() {
                            observer.onComplete();
                        }
                    });
                }

                for (final StreamingIterator iterator : iterators) {
                    iterator.queue(SENTINEL);
                }
            }
            return result;
        } else {
            return complete();
        }
    }

    protected abstract void accumulate(E element);
    protected abstract Iterable<E> drain();
    protected abstract boolean complete();

    private void dispatch(final E element) {

        for (final Observer<E> observer : observers) {
            observer.getExecutor().execute(new Runnable() {
                @Override
                public void run() {
                    observer.onNext(element);
                }
            });
        }
        for (final StreamingIterator iterator : iterators) {
            iterator.queue(element);
        }
    }

    @Override
    public boolean fail(final Throwable throwable) {

        if (super.fail(throwable)) {
            synchronized(this) {
                error = throwable;

                for (final Observer<E> observer : observers) {
                    observer.getExecutor().execute(new Runnable() {
                        @Override
                        public void run() {
                            observer.onError(throwable);
                        }
                    });
                }
                for (final StreamingIterator iterator : iterators) {
                    iterator.queue(SENTINEL);
                }

                return true;
            }
        }

        return false;
    }

    @Override
    public void addObserver(final Observer<E> observer) {

        synchronized (this) {
            observers.add(observer);

            if (streaming && !drained) {
                drained = true;
                for (final E element : drain()) {
                    observer.getExecutor().execute(new Runnable() {
                        @Override
                        public void run() {
                            observer.onNext(element);
                        }
                    });
                }
            }

            if (isDone()) {
                observer.getExecutor().execute(new Runnable() {
                    @Override
                    public void run() {
                        observer.onComplete();
                    }
                });
            }
        }
    }

    /**
     * In order to conform to the Iterator interface, this implementation propagates
     * {@link InterruptedException} on blocking calls by wrapping them in a
     * {@link RuntimeException}.
     */
    @Override
    public Iterator<E> iterator() {

        StreamingIterator i;
        if (streaming && !drained) {
            synchronized (this) {
                if (streaming && !drained) {
                    drained = true;
                    i = new StreamingIterator(drain().iterator());
                    if (isDone()) i.queue(SENTINEL);
                    iterators.add(i);
                    return i;
                }
            }
        }

        i = new StreamingIterator(null);
        if (isDone()) i.queue(SENTINEL);
        iterators.add(i);
        return i;
    }

    private class StreamingIterator implements Iterator<E> {

        private BlockingQueue<Object> queue = new LinkedBlockingQueue<>();
        private Iterator<E> drained;
        Object currentElement;

        StreamingIterator(Iterator<E> drained) {
            this.drained = drained;
        }

        private void queue(Object o) {
            queue.add(o);
        }

        @Override
        public boolean hasNext() {

            if ((drained != null && drained.hasNext()) || (currentElement != null && currentElement != SENTINEL)) {
                return true;
            }

            if (isDone() && queue.isEmpty()) return false;

            synchronized(ReactiveFuture.this) {
                if (isDone() && queue.isEmpty()) return false;
            }

            try {
                currentElement = queue.take();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }

            if (currentElement != SENTINEL) return true;
            if (error != null) throw new RuntimeException(error);

            return false;
        }

        @SuppressWarnings("unchecked")
        @Override
        public E next() {

            if (drained != null && drained.hasNext()) return drained.next();

            if (currentElement != null && currentElement != SENTINEL) {
                E element = (E) currentElement;
                currentElement = null;
                return element;
            }

            if (isDone() && queue.isEmpty()) throw new NoSuchElementException();

            synchronized (ReactiveFuture.this) {
                if (isDone() && queue.isEmpty()) throw new NoSuchElementException();
            }

            if (!hasNext()) throw new NoSuchElementException();
            E element = (E) currentElement;
            currentElement = null;
            return element;
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }
    }
}
