/*
 * Copyright 2019 Databricks, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.delta.util

import scala.collection.mutable.ArrayBuffer

import org.apache.spark.sql.delta.Snapshot

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{DataFrame, Dataset, SparkSession}
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.execution.LogicalRDD
import org.apache.spark.storage.StorageLevel

/**
 * Machinary that caches the reconstructed state of a Delta table
 * using the RDD cache. The cache is designed so that the first access
 * will materialize the results.  However once uncache is called,
 * all data will be flushed and will not be cached again.
 */
trait StateCache {
  protected def spark: SparkSession

  /** If state RDDs for this snapshot should still be cached. */
  private var isCached = true
  /** A list of RDDs that we need to uncache when we are done with this snapshot. */
  private val cached = ArrayBuffer[RDD[_]]()

  class CachedDS[A](ds: Dataset[A], name: String) {
    // While `rddCache` can be reused by different spark sessions, `dsCache` can only be reused
    // by the session that created this cachedDS; so it's an optimization only for single-session
    // scenarios.
    private val (rddCache, dsCache) = cached.synchronized {
      if (isCached) {
        val rdd = ds.queryExecution.toRdd.map(_.copy())
        rdd.setName(name)
        rdd.persist(StorageLevel.MEMORY_AND_DISK_SER)
        cached += rdd
        val cachedDs = Dataset.ofRows(
          spark,
          LogicalRDD(
            ds.queryExecution.analyzed.output,
            rdd)(
            spark)).as[A](ds.exprEnc)

        (Some(rdd), Some(cachedDs))
      } else {
        (None, None)
      }
    }

    /**
     * Get the DS from the cache.
     *
     * If a RDD cache is available,
     * - return the cached DS if called from the same session in which the cached DS is created, or
     * - reconstruct the DS using the RDD cache if called from a different session.
     *
     * If no RDD cache is available,
     * - return a copy of the original DS with updated spark session.
     *
     * Since a cached DeltaLog can be accessed from multiple Spark sessions, this interface makes
     * sure that the original Spark session in the cached DS does not leak into the current active
     * sessions.
     */
    def getDS: Dataset[A] = {
      if (cached.synchronized(isCached) && rddCache.isDefined) {
        if (dsCache.exists(_.sparkSession eq spark)) {
          dsCache.get
        } else {
          Dataset.ofRows(
            spark,
            LogicalRDD(
              ds.queryExecution.analyzed.output,
              rddCache.get)(
              spark)).as[A](ds.exprEnc)
        }
      } else {
        Dataset.ofRows(
          spark,
          ds.queryExecution.logical
        ).as[A](ds.exprEnc)
      }
    }
  }

  /**
   * Create a CachedDS instance for the given Dataset and the name.
   */
  def cacheDS[A](ds: Dataset[A], name: String): CachedDS[A] = {
    new CachedDS[A](ds, name)
  }

  /** Drop any cached data for this [[Snapshot]]. */
  def uncache(): Unit = cached.synchronized {
    if (isCached) {
      isCached = false
      cached.foreach(_.unpersist(blocking = false))
    }
  }
}
