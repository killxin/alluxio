/*
 * Licensed to the University of California, Berkeley under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package tachyon.replay;

import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import com.google.common.base.Throwables;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.util.concurrent.UncheckedExecutionException;

import tachyon.exception.TachyonException;
import tachyon.thrift.TachyonTException;
import tachyon.thrift.ThriftIOException;

/**
 * An RPC cache which uses RPC ids to avoid repeating non-idempotent RPCs due to retries.
 *
 * Whenever a {@link RetryCallable} is run via this class, the RPC id and return value for the
 * {@link RetryCallable} are remembered so that if the RPC id is replayed while the id is still in
 * the cache, the return value can be immediately returned without executing the
 * {@link RetryCallable}.
 *
 * For RPCs which may throw {@link IOException}, use {@link ReplayCallableThrowsIOException}.
 *
 * Example usage:<br>
 *
 * <pre>
 * <code>
 * private final Cache<String, Long> cache = ReplayCache.create();
 * ...
 * public long myRpc(final boolean val1, final int val2, String rpcId) throws TachyonTException {
 *   return mCache.run(rpcId, new ReplayCallable<Long>() {
 *     @Override
 *     public Long call() throws TachyonException {
 *       return rpcWhichCanThrowTachyonException(val1, val2);
 *     }
 *   });
 * }
 * </code>
 * </pre>
 *
 * @param <V> the type of value returned by this cache
 */
public final class ReplayCache<V> {
  /** The maximum number of keys that can be cached before old keys are evicted. */
  private static final long DEFAULT_MAX_SIZE = 10000;
  /** The number of milliseconds a key may remain unaccessed in the cache before it is evicted. */
  private static final long DEFAULT_EXPIRE_MS = 2000;

  private final Cache<String, V> mCache;

  /**
   * Creates a cache with default maximum size and key expiration time.
   *
   * @return a cache with the default maximum size and expiration time
   */
  public static <V> ReplayCache<V> newReplayCache() {
    return new ReplayCache<V>();
  }

  /**
   * Creates a cache with the given maximum size and key expiration time.
   *
   * @param maxSize the maximum number of elements the cache may hold
   * @param expireMs the amount of time to hold entries before they expire
   * @return a cache with the specified maximum size and expiration time
   */
  public static <V> ReplayCache<V> newReplayCache(long maxSize, long expireMs) {
    return new ReplayCache<V>(maxSize, expireMs);
  }

  /**
   * Creates a cache with default maximum size and key expiration time.
   */
  private ReplayCache() {
    this(DEFAULT_MAX_SIZE, DEFAULT_EXPIRE_MS);
  }

  /**
   * Creates a cache with the given maximum size and key expiration time.
   *
   * @param maxSize the maximum number of elements the cache may hold
   * @param expireMs the amount of time to hold entries before they expire
   */
  private ReplayCache(long maxSize, long expireMs) {
    // TODO(andrew) Make it possible for users to configure this cache via
    // CacheBuilder<Object, Object> from(String spec)
    mCache = CacheBuilder.newBuilder().maximumSize(maxSize)
        .expireAfterWrite(expireMs, TimeUnit.MILLISECONDS).<String, V>build();
  }

  /**
   * The RPC handler method to be executed in {@link #run(String, ReplayCallable)}.
   *
   * @param <V> the return type of {@link #call()}
   */
  public interface ReplayCallable<V> {
    V call() throws TachyonException;
  }

  /**
   * Same with {@link ReplayCallable} except that this handler method throws
   * {@link IOException} and is to be executed in {@link #run(String,
   * ReplayCallableThrowsIOException}.
   *
   * @param <V> the return type of {@link #call()}
   */
  public interface ReplayCallableThrowsIOException<V> {
    V call() throws TachyonException, IOException;
  }

  /**
   * Handles the replay logic for the given RPC handler. The provided rpcId is used to manage
   * replays by returning their previous return value instead of re-executing the RPC handler. This
   * is neccessary when the RPC is non-idempotent.
   *
   * @param rpcId the id for the RPC
   * @param replayCallable the handler logic for the RPC
   * @return the result of executing replayCallable, or the cached value from a previous invocation
   * @throws TachyonTException when {@link TachyonException} is thrown by the handler call
   */
  public V run(String rpcId, final ReplayCallable<V> replayCallable) throws TachyonTException {
    try {
      return mCache.get(rpcId, new Callable<V>() {
        @Override
        public V call() throws Exception {
          try {
            return replayCallable.call();
          } catch (TachyonException e) {
            throw e.toTachyonTException();
          }
        }
      });
    } catch (ExecutionException e) {
      Throwables.propagateIfInstanceOf(e.getCause(), TachyonTException.class);
      throw Throwables.propagate(e.getCause());
    } catch (UncheckedExecutionException e) {
      throw Throwables.propagate(e.getCause());
    }
  }

  /**
   * Similar to {@link #run(String, ReplayCallable)} except that the RPC handler may throw
   * {@link IOException}, which will be transformed into {@link ThriftIOException}.
   *
   * @param rpcId the id for the RPC
   * @param replayCallable the handler logic for the RPC
   * @return the result of executing replayCallable, or the cached value from a previous invocation
   * @throws TachyonTException when {@link TachyonException} is thrown by the handler call
   * @throws ThriftIOException when {@link IOException} is thrown by the handler call
   */
  public V run(String rpcId, final ReplayCallableThrowsIOException<V> replayCallable)
      throws TachyonTException, ThriftIOException {
    try {
      return mCache.get(rpcId, new Callable<V>() {
        @Override
        public V call() throws Exception {
          try {
            return replayCallable.call();
          } catch (TachyonException e) {
            throw e.toTachyonTException();
          } catch (IOException e) {
            throw new ThriftIOException(e.getMessage());
          }
        }
      });
    } catch (ExecutionException e) {
      Throwables.propagateIfInstanceOf(e.getCause(), TachyonTException.class);
      Throwables.propagateIfInstanceOf(e.getCause(), ThriftIOException.class);
      throw Throwables.propagate(e.getCause());
    } catch (UncheckedExecutionException e) {
      throw Throwables.propagate(e.getCause());
    }
  }
}
