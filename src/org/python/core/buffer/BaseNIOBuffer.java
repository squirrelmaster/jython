package org.python.core.buffer;

import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;

import org.python.core.PyBUF;
import org.python.core.PyBuffer;
import org.python.core.PyException;

/**
 * Base implementation of the Buffer API for when the storage implementation is
 * <code>java.nio.ByteBuffer</code>. The description of {@link BaseBuffer} mostly applies. Methods
 * provided or overridden here are appropriate to 1-dimensional arrays, of any item size, backed by
 * a <code>ByteBuffer</code>.
 */
public abstract class BaseNIOBuffer extends Base1DBuffer {

    /**
     * A {@link java.nio.ByteBuffer} (possibly a direct buffer) wrapping the storage that the
     * exporter is sharing with the consumer. The data to be exposed may be only a subset of the
     * bytes in the buffer, defined by the navigation information <code>index0</code>,
     * <code>shape</code>, <code>strides</code>, etc., usually defined in the constructor.
     * <p>
     * Implementations must not adjust the position and limit of <code>storage</code> after
     * construction. It will generally be a duplicate of (not a reference to) a ByteBuffer held by
     * the client code. The capacity and backing store are fixed in construction, and the position
     * will always be {@link #index0}. The limit is always higher than any valid data, and in the
     * case of a contiguous buffer (with positive stride), is exactly just beyond the last item, so
     * that a series of ByteBuffer.get operations will yield the data.
     */
    protected ByteBuffer storage;

    /**
     * Construct an instance of <code>BaseNIOBuffer</code> in support of a sub-class, specifying the
     * 'feature flags', or at least a starting set to be adjusted later. Also specify the navigation
     * ( {@link #index0}, number of elements, and stride. These 'feature flags' are the features of
     * the buffer exported, not the flags that form the consumer's request. The buffer will be
     * read-only unless {@link PyBUF#WRITABLE} is set. {@link PyBUF#FORMAT} and
     * {@link PyBUF#AS_ARRAY} are implicitly added to the feature flags.
     * <p>
     * To complete initialisation, the sub-class normally must call {@link #checkRequestFlags(int)}
     * passing the consumer's request flags.
     *
     * @param storage the <code>ByteBuffer</code> wrapping the exported object state. NOTE: this
     *            <code>PyBuffer</code> keeps a reference and may manipulate the position, mark and
     *            limit hereafter. Use {@link ByteBuffer#duplicate()} to give it an isolated copy.
     * @param featureFlags bit pattern that specifies the features allowed
     * @param index0 index into storage of <code>item[0]</code>
     * @param size number of elements in the view
     * @param stride byte-index step between successive elements
     */

    protected BaseNIOBuffer(ByteBuffer storage, int featureFlags, int index0, int size, int stride) {
        super(featureFlags & ~(WRITABLE | AS_ARRAY), index0, size, stride);
        this.storage = storage;

        // Deduce other feature flags from the client's ByteBuffer
        if (!storage.isReadOnly()) {
            addFeatureFlags(WRITABLE);
        }
        if (storage.hasArray()) {
            addFeatureFlags(AS_ARRAY);
        }
    }

    @Override
    protected byte byteAtImpl(int byteIndex) throws IndexOutOfBoundsException {
        return storage.get(byteIndex);
    }

    @Override
    protected void storeAtImpl(byte value, int byteIndex) throws IndexOutOfBoundsException,
            PyException {
        // XXX consider catching ReadonlyBufferException instead of checking (and others: index?)
        checkWritable();
        storage.put(byteIndex, value);
    }

    @Override
    protected int byteIndex(int... indices) throws IndexOutOfBoundsException {
        // BaseBuffer implementation can be simplified since if indices.length!=1 we error.
        checkDimension(indices.length); // throws if != 1
        return byteIndex(indices[0]);
    }

    /**
     * {@inheritDoc}
     * <p>
     * The default implementation in <code>BaseNIOBuffer</code> deals with the general
     * one-dimensional case of arbitrary item size and stride.
     */
    @Override
    public void copyTo(int srcIndex, byte[] dest, int destPos, int count)
            throws IndexOutOfBoundsException {
        // Wrap the destination, taking care to reflect the necessary range we shall write.
        ByteBuffer destBuf = ByteBuffer.wrap(dest, destPos, count * getItemsize());
        copyTo(srcIndex, destBuf, count);
    }

    /**
     * {@inheritDoc}
     * <p>
     * The default implementation in <code>BaseBuffer</code> deals with the general one-dimensional
     * case of arbitrary item size and stride.
     */
    // XXX Should this become part of the PyBUffer interface?
    public void copyTo(ByteBuffer dest) throws BufferOverflowException, ReadOnlyBufferException,
            PyException {
        // Note shape[0] is the number of items in the buffer
        copyTo(0, dest, shape[0]);
    }

    /**
     * {@inheritDoc}
     * <p>
     * The default implementation in <code>BaseNIOBuffer</code> deals with the general
     * one-dimensional case of arbitrary item size and stride.
     */
    // XXX Should this become part of the PyBuffer interface?
    protected void copyTo(int srcIndex, ByteBuffer dest, int count) throws BufferOverflowException,
            ReadOnlyBufferException, IndexOutOfBoundsException, PyException {

        if (count > 0) {

            ByteBuffer src = getNIOByteBuffer(srcIndex);

            // Pick up attributes necessary to choose an efficient copy strategy
            int itemsize = getItemsize();
            int stride = getStrides()[0];

            // Strategy depends on whether items are laid end-to-end contiguously or there are gaps
            if (stride == itemsize) {
                // stride == itemsize: straight copy of contiguous bytes
                src.limit(src.position() + count * itemsize);
                dest.put(src);

            } else if (itemsize == 1) {
                // Non-contiguous copy: single byte items
                int pos = src.position();
                for (int i = 0; i < count; i++) {
                    src.position(pos);
                    dest.put(src.get());
                    pos += stride;
                }

            } else {
                // Non-contiguous copy: each time, copy itemsize bytes then skip
                int pos = src.position();
                for (int i = 0; i < count; i++) {
                    src.limit(pos + itemsize).position(pos);
                    dest.put(src);
                    pos += stride;
                }
            }
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * The default implementation in <code>BaseNIOBuffer</code> deals with the general
     * one-dimensional case of arbitrary item size and stride.
     */
    @Override
    public void copyFrom(byte[] src, int srcPos, int destIndex, int count)
            throws IndexOutOfBoundsException, PyException {
        // Wrap the source, taking care to reflect the range we shall read.
        ByteBuffer srcBuf = ByteBuffer.wrap(src, srcPos, count * getItemsize());
        copyFrom(srcBuf, destIndex, count);
    }

    /**
     * {@inheritDoc}
     * <p>
     * The default implementation in <code>BaseNIOBuffer</code> deals with the general
     * one-dimensional case of arbitrary item size and stride.
     */
    // XXX Should this become part of the PyBUffer interface?
    protected void copyFrom(ByteBuffer src, int dstIndex, int count)
            throws IndexOutOfBoundsException, PyException {

        checkWritable();

        if (count > 0) {

            ByteBuffer dst = getNIOByteBuffer(dstIndex);

            // Pick up attributes necessary to choose an efficient copy strategy
            int itemsize = getItemsize();
            int stride = getStrides()[0];
            int skip = stride - itemsize;

            // Strategy depends on whether items are laid end-to-end or there are gaps
            if (skip == 0) {
                // Straight copy of contiguous bytes
                dst.put(src);

            } else if (itemsize == 1) {
                // Non-contiguous copy: single byte items
                int pos = dst.position();
                for (int i = 0; i < count; i++) {
                    dst.position(pos);
                    dst.put(src.get());
                    // Next byte written will be here
                    pos += stride;
                }

            } else {
                // Non-contiguous copy: each time, copy itemsize bytes at a time
                int pos = dst.position();
                for (int i = 0; i < count; i++) {
                    dst.position(pos);
                    // Delineate the next itemsize bytes in the src
                    src.limit(src.position() + itemsize);
                    dst.put(src);
                    // Next byte written will be here
                    pos += stride;
                }
            }
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * The default implementation in <code>BaseNIOBuffer</code> deals with the general
     * one-dimensional case.
     */
    @Override
    public void copyFrom(PyBuffer src) throws IndexOutOfBoundsException, PyException {

        int length = getLen();
        int itemsize = getItemsize();

        // Valid operation only if writable and same length and itemsize
        checkWritable();
        if (src.getLen() != length || src.getItemsize() != itemsize) {
            throw differentStructure();
        }

        if (length > 0) {
            // Pick up attributes necessary to choose an efficient copy strategy
            int stride = getStrides()[0];
            int skip = stride - itemsize;

            ByteBuffer dst = getNIOByteBuffer();

            // Strategy depends on whether destination items are laid end-to-end or there are gaps
            if (skip == 0) {
                // Straight copy to contiguous bytes
                for (int i = 0; i < length; i++) {
                    dst.put(src.byteAt(i));
                }

            } else if (itemsize == 1) {
                // Non-contiguous copy: single byte items
                int pos = dst.position();
                for (int i = 0; i < length; i++) {
                    dst.put(pos, src.byteAt(i));
                    pos += stride;
                }

            } else {
                // Non-contiguous copy: each time, and itemsize > 1
                int pos = dst.position();
                int s = 0;
                for (int i = 0; i < length; i++) {
                    for (int j = 0; j < itemsize; j++) {
                        dst.put(pos++, src.byteAt(s++));
                    }
                    pos += skip;
                }
            }
        }

    }

    @Override
    protected ByteBuffer getNIOByteBufferImpl() {
        return storage.duplicate();
    }

    @SuppressWarnings("deprecation")
    @Override
    public Pointer getBuf() {
        checkHasArray();
        return new Pointer(storage.array(), index0);
    }
}
