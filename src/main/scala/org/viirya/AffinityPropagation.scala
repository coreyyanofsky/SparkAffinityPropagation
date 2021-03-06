
package org.viirya

import scala.collection.mutable

import org.apache.spark.{Logging, SparkException}
import org.apache.spark.annotation.Experimental
import org.apache.spark.api.java.JavaRDD
import org.apache.spark.graphx._
import org.apache.spark.graphx.impl.GraphImpl
import org.apache.spark.rdd.RDD
import org.apache.spark.storage.StorageLevel

/**
 * :: Experimental ::
 *
 * Model produced by [[AffinityPropagation]].
 *
 * @param id cluster id.
 * @param exemplar cluster exemplar.
 * @param member cluster member.
 */
@Experimental
case class AffinityPropagationAssignment(val id: Long, val exemplar: Long, val member: Long)

/**
 * :: Experimental ::
 *
 * Model produced by [[AffinityPropagation]].
 *
 * @param id cluster id.
 * @param exemplar cluster exemplar.
 * @param members cluster member.
 */
@Experimental
case class AffinityPropagationCluster(val id: Long, val exemplar: Long, val members: Array[Long])

/**
 * :: Experimental ::
 *
 * Model produced by [[AffinityPropagation]].
 *
 * @param assignments the cluster assignments of AffinityPropagation clustering results.
 */
@Experimental
class AffinityPropagationModel(
    val assignments: RDD[AffinityPropagationAssignment]) extends Serializable {

  /**
   * Get the number of clusters
   */
  lazy val k: Long = assignments.map(_.id).distinct.count()
 
  /**
   * Find the cluster the given vertex belongs
   * @param vertexID vertex id.
   * @return a [[RDD]] that contains vertex ids in the same cluster of given vertexID. If
   *         the given vertex doesn't belong to any cluster, return null.
   */
  def findCluster(vertexID: Long): RDD[Long] = {
    val assign = assignments.filter(_.member == vertexID).collect()
    if (assign.nonEmpty) {
      assignments.filter(_.id == assign(0).id).map(_.member)
    } else {
      assignments.sparkContext.emptyRDD[Long]
    }
  } 
 
  /**
   * Find the cluster id the given vertex belongs to
   * @param vertexID vertex id.
   * @return the cluster id that the given vertex belongs to. If the given vertex doesn't belong to
   *         any cluster, return -1.
   */
  def findClusterID(vertexID: Long): Long = {
    val assign = assignments.filter(_.member == vertexID).collect()
    if (assign.nonEmpty) {
      assign(0).id
    } else {
      -1
    }
  } 

  /**
   * Turn cluster assignments to cluster representations [[AffinityPropagationCluster]].
   * @return a [[RDD]] that contains all clusters generated by Affinity Propagation. Because the
   * cluster members in [[AffinityPropagationCluster]] is an [[Array]], it could consume too much
   * memory even run out of memory when you call collect() on the returned [[RDD]].
   */
  def fromAssignToClusters(): RDD[AffinityPropagationCluster] = {
    assignments.map { assign => ((assign.id, assign.exemplar), assign.member) }
      .aggregateByKey(mutable.Set[Long]())(
        seqOp = (s, d) => s ++ mutable.Set(d),
        combOp = (s1, s2) => s1 ++ s2
      ).map(kv => new AffinityPropagationCluster(kv._1._1, kv._1._2, kv._2.toArray))
  }
}

/**
 * The message exchanged on the node graph
 */
case class EdgeMessage(
    similarity: Double,
    availability: Double,
    responsibility: Double) extends Equals {
  override def canEqual(that: Any): Boolean = {
    that match {
      case e: EdgeMessage =>
        similarity == e.similarity && availability == e.availability &&
          responsibility == e.responsibility
      case _ =>
        false
    }
  }
}

/**
 * The data stored in each vertex on the graph
 */
case class VertexData(availability: Double, responsibility: Double)

/**
 * :: Experimental ::
 *
 * Affinity propagation (AP), a graph clustering algorithm based on the concept of "message passing"
 * between data points. Unlike clustering algorithms such as k-means or k-medoids, AP does not
 * require the number of clusters to be determined or estimated before running it. AP is developed
 * by [[http://doi.org/10.1126/science.1136800 Frey and Dueck]].
 *
 * @param maxIterations Maximum number of iterations of the AP algorithm.
 * @param lambda lambda parameter used in the messaging iteration loop
 * @param normalization Indication of performing normalization
 * @param symmetric Indication of using symmetric similarity input
 *
 * @see [[http://en.wikipedia.org/wiki/Affinity_propagation Affinity propagation (Wikipedia)]]
 */
@Experimental
class AffinityPropagation (
    private var maxIterations: Int,
    private var lambda: Double,
    private var normalization: Boolean,
    private var symmetric: Boolean) extends Serializable {

  import org.viirya.AffinityPropagation._

  /** Constructs a AP instance with default parameters: {maxIterations: 100, lambda: `0.5`,
   *    normalization: false, symmetric: true}.
   */
  def this() = this(maxIterations = 100, lambda = 0.5, normalization = false, symmetric = true)

  /**
   * Set maximum number of iterations of the messaging iteration loop
   */
  def setMaxIterations(maxIterations: Int): this.type = {
    this.maxIterations = maxIterations
    this
  }
 
  /**
   * Get maximum number of iterations of the messaging iteration loop
   */
  def getMaxIterations: Int = {
    this.maxIterations
  }
 
  /**
   * Set lambda of the messaging iteration loop
   */
  def setLambda(lambda: Double): this.type = {
    this.lambda = lambda
    this
  }
 
  /**
   * Get lambda of the messaging iteration loop
   */
  def getLambda(): Double = {
    this.lambda
  }
 
  /**
   * Set whether to do normalization or not
   */
  def setNormalization(normalization: Boolean): this.type = {
    this.normalization = normalization
    this
  }
 
  /**
   * Get whether to do normalization or not
   */
  def getNormalization(): Boolean = {
    this.normalization
  }

  /**
   * Set whether the input similarities are symmetric or not.
   * When symmetric is set to true, we assume that input similarities only contain triangular
   * matrix. That means, only s,,ij,, is included in the similarities. If both s,,ij,, and
   * s,,ji,, are given in the similarities, it very possibly causes error.
   */
  def setSymmetric(symmetric: Boolean): this.type = {
    this.symmetric = symmetric
    this
  }

  /**
   * Get whether the input similarities are symmetric or not
   */
  def getSymmetric(): Boolean = {
    this.symmetric
  }

  /**
   * Calculate the median value of similarities
   */
  private def getMedian(similarities: RDD[(Long, Long, Double)]): Double = {
    val sorted: RDD[(Long, Double)] = similarities.sortBy(_._3).zipWithIndex().map {
      case (v, idx) => (idx, v._3)
    }.persist(StorageLevel.MEMORY_AND_DISK)

    val count = sorted.count()

    val median: Double =
      if (count % 2 == 0) {
        val l = count / 2 - 1
        val r = l + 1
        (sorted.lookup(l).head + sorted.lookup(r).head).toDouble / 2
      } else {
        sorted.lookup(count / 2).head
      }
    sorted.unpersist()
    median
  }

  /**
   * Determine preferences by calculating median of similarities.
   * This might cost considering computation time for large similarities data.
   */
  def determinePreferences(
      similarities: RDD[(Long, Long, Double)]): RDD[(Long, Long, Double)] = {
    // the recommended preferences is the median of similarities
    val median = getMedian(similarities)
    val preferences = similarities.flatMap(t => Seq(t._1, t._2)).distinct().map(i => (i, i, median))
    similarities.union(preferences)
  }
 
  /**
   * A Java-friendly version of [[AffinityPropagation.determinePreferences]].
   */
  def determinePreferences(
      similarities: JavaRDD[(java.lang.Long, java.lang.Long, java.lang.Double)]): 
      RDD[(Long, Long, Double)] = {
    determinePreferences(similarities.rdd.asInstanceOf[RDD[(Long, Long, Double)]])
  }

  /**
   * Manually set up preferences for tuning cluster size.
   */
  def embedPreferences(
      similarities: RDD[(Long, Long, Double)],
      preference: Double): RDD[(Long, Long, Double)] = {
    val preferences = similarities.flatMap(t => Seq(t._1, t._2)).distinct()
      .map(i => (i, i, preference))
    similarities.union(preferences)
  }

  /**
   * A Java-friendly version of [[AffinityPropagation.embedPreferences]].
   */
  def embedPreferences(
      similarities: JavaRDD[(java.lang.Long, java.lang.Long, java.lang.Double)],
      preference: Double): RDD[(Long, Long, Double)] = {
    embedPreferences(similarities.rdd.asInstanceOf[RDD[(Long, Long, Double)]], preference)
  }
 
  /**
   * Run the AP algorithm.
   *
   * @param similarities an RDD of (i, j, s,,ij,,) tuples representing the similarity matrix, which
   *                     is the matrix S in the AP paper. The similarity s,,ij,, is set to
   *                     real-valued (could be positive or negative) similarities. This is not
   *                     required to be a symmetric matrix and hence s,,ij,, can be different from
   *                     s,,ji,,. Tuples with i = j are referred to as "preferences" in the paper.
   *                     The data points with larger values of s,,ii,, are more likely to be chosen
   *                     as exemplars.
   *
   * @return a [[AffinityPropagationModel]] that contains the clustering result
   */
  def run(similarities: RDD[(Long, Long, Double)])
    : AffinityPropagationModel = {
    val s = constructGraph(similarities, normalization, this.symmetric)
    ap(s)
  }

  /**
   * A Java-friendly version of [[AffinityPropagation.run]].
   */
  def run(similarities: JavaRDD[(java.lang.Long, java.lang.Long, java.lang.Double)])
    : AffinityPropagationModel = {
    run(similarities.rdd.asInstanceOf[RDD[(Long, Long, Double)]])
  }

  /**
   * Runs the AP algorithm.
   *
   * @param s The (normalized) similarity matrix, which is the matrix S in the AP paper with vertex
   *          similarities and the initial availabilities and responsibilities as its edge
   *          properties.
   */
  private def ap(s: Graph[VertexData, EdgeMessage]): AffinityPropagationModel = {
    val g = apIter(s, maxIterations, lambda)
    chooseExemplars(g)
  }
}

object AffinityPropagation extends Logging {
  /**
   * Construct the similarity matrix (S) and do normalization if needed.
   * Returns the (normalized) similarity matrix (S).
   */
  def constructGraph(similarities: RDD[(Long, Long, Double)],
      normalize: Boolean,
      symmetric: Boolean): Graph[VertexData, EdgeMessage] = {
    val edges = similarities.flatMap { case (i, j, s) =>
      if (symmetric && i != j) {
        Seq(Edge(i, j, new EdgeMessage(s, 0.0, 0.0)), Edge(j, i, new EdgeMessage(s, 0.0, 0.0)))
      } else {
        Seq(Edge(i, j, new EdgeMessage(s, 0.0, 0.0)))
      }
    }

    if (normalize) {
      val gA = Graph.fromEdges(edges, 0.0)
      val vD = gA.aggregateMessages[Double](
        sendMsg = ctx => {
          ctx.sendToSrc(ctx.attr.similarity)
        },
        mergeMsg = (s1, s2) => s1 + s2,
        TripletFields.EdgeOnly)
      val normalized = GraphImpl.fromExistingRDDs(vD, gA.edges)
        .mapTriplets({ e =>
            val s = if (e.srcAttr == 0.0) e.attr.similarity else e.attr.similarity / e.srcAttr
            new EdgeMessage(s, 0.0, 0.0)
        }, TripletFields.Src)
      Graph.fromEdges(normalized.edges, new VertexData(0.0, 0.0))
    } else {
      Graph.fromEdges(edges, new VertexData(0.0, 0.0))
    }
  }

  /**
   * Runs AP's iteration.
   * @param g input graph with edges representing the (normalized) similarity matrix (S) and
   *          the initial availabilities and responsibilities.
   * @param maxIterations maximum number of iterations.
   * @return a [[Graph]] representing the final graph.
   */
  def apIter(
      g: Graph[VertexData, EdgeMessage],
      maxIterations: Int,
      lambda: Double): Graph[VertexData, EdgeMessage] = {
    val tol = math.max(1e-5 / g.vertices.count(), 1e-8)
    var prevDelta = (Double.MaxValue, Double.MaxValue)
    var diffDelta = (Double.MaxValue, Double.MaxValue)
    var curG = g
    for (iter <- 0 until maxIterations
      if math.abs(diffDelta._1) > tol || math.abs(diffDelta._2) > tol) {
      val msgPrefix = s"Iteration $iter"

      // update responsibilities
      val vD_r = curG.aggregateMessages[Seq[Double]](
        sendMsg = ctx => ctx.sendToSrc(Seq(ctx.attr.similarity + ctx.attr.availability)),
        mergeMsg = _ ++ _,
        TripletFields.EdgeOnly)

      val updated_r = GraphImpl(vD_r, curG.edges)
        .mapTriplets({ e =>
          val filtered = e.srcAttr.filter(_ != (e.attr.similarity + e.attr.availability))
          val pool = if (filtered.size < e.srcAttr.size - 1) {
            filtered :+ (e.attr.similarity + e.attr.availability)
          } else {
            filtered
          }
          val maxValue = if (pool.isEmpty) 0.0 else pool.max
          new EdgeMessage(e.attr.similarity,
            e.attr.availability,
            lambda * (e.attr.similarity - maxValue) + (1.0 - lambda) * e.attr.responsibility)
        }, TripletFields.Src)

      var iterG = Graph.fromEdges(updated_r.edges, new VertexData(0.0, 0.0))

      // update availabilities
      val vD_a = iterG.aggregateMessages[Double](
        sendMsg = ctx => {
          if (ctx.srcId != ctx.dstId) {
            ctx.sendToDst(math.max(ctx.attr.responsibility, 0.0))
          } else {
            ctx.sendToDst(ctx.attr.responsibility)
          }
        }, mergeMsg = (s1, s2) => s1 + s2,
        TripletFields.EdgeOnly)

      val updated_a = GraphImpl(vD_a, iterG.edges)
        .mapTriplets(
          (e) => {
            if (e.srcId != e.dstId) {
              val newA = lambda * math.min(0.0, e.dstAttr - math.max(e.attr.responsibility, 0.0)) +
                         (1.0 - lambda) * e.attr.availability
              new EdgeMessage(e.attr.similarity, newA, e.attr.responsibility)
            } else {
              val newA = lambda * (e.dstAttr - e.attr.responsibility) +
                (1.0 - lambda) * e.attr.availability
              new EdgeMessage(e.attr.similarity, newA, e.attr.responsibility)
            }
          }, TripletFields.Dst)

      iterG = Graph.fromEdges(updated_a.edges, new VertexData(0.0, 0.0))

      // compare difference
      if (iter % 10 == 0) {
        val vaD = iterG.aggregateMessages[VertexData](
          sendMsg = ctx =>
            ctx.sendToSrc(new VertexData(ctx.attr.availability, ctx.attr.responsibility)),
          mergeMsg = (s1, s2) =>
            new VertexData(s1.availability + s2.availability,
              s1.responsibility + s2.responsibility),
          TripletFields.EdgeOnly)

        val prev_vaD = curG.aggregateMessages[VertexData](
          sendMsg = ctx =>
            ctx.sendToSrc(new VertexData(ctx.attr.availability, ctx.attr.responsibility)),
          mergeMsg = (s1, s2) =>
            new VertexData(s1.availability + s2.availability,
              s1.responsibility + s2.responsibility),
          TripletFields.EdgeOnly)

        val delta = vaD.join(prev_vaD).values.map { x =>
          (x._1.availability - x._2.availability, x._1.responsibility - x._2.responsibility)
        }.collect().foldLeft((0.0, 0.0)) {(s, t) => (s._1 + t._1, s._2 + t._2)}

        logInfo(s"$msgPrefix: availability delta = ${delta._1}.")
        logInfo(s"$msgPrefix: responsibility delta = ${delta._2}.")

        diffDelta = (math.abs(delta._1 - prevDelta._1), math.abs(delta._2 - prevDelta._2))
        
        logInfo(s"$msgPrefix: diff(delta) = $diffDelta.")

        prevDelta = delta
      }
      curG = iterG
    }
    curG
  }
 
  /**
   * Choose exemplars for nodes in graph.
   * @param g input graph with edges representing the final availabilities and responsibilities.
   * @return a [[AffinityPropagationModel]] representing the clustering results.
   */
  def chooseExemplars(
      g: Graph[VertexData, EdgeMessage]): AffinityPropagationModel = {
    val accum = g.edges.map(a => (a.srcId, (a.dstId, a.attr.availability + a.attr.responsibility)))
    val clusterMembers = accum.reduceByKey((ar1, ar2) => {
      if (ar1._2 > ar2._2) {
        (ar1._1, ar1._2)
      } else {
        (ar2._1, ar2._2)
      }
    }).map(kv => (kv._2._1, kv._1)).aggregateByKey(mutable.Set[Long]())(
      seqOp = (s, d) => s ++ mutable.Set(d),
      combOp = (s1, s2) => s1 ++ s2
    ).cache()
    
    val assignments = clusterMembers.zipWithIndex().flatMap { kv =>
      kv._1._2.map(new AffinityPropagationAssignment(kv._2, kv._1._1, _))
    }

    new AffinityPropagationModel(assignments)
  }
}
