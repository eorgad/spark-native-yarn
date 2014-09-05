package org.apache.spark.tez

import org.apache.spark.Dependency
import org.apache.spark.InterruptibleIterator
import org.apache.spark.OneToOneDependency
import org.apache.spark.Partition
import org.apache.spark.SparkEnv
import org.apache.spark.TaskContext
import org.apache.spark.rdd.CoGroupPartition
import org.apache.spark.rdd.CoGroupSplitDep
import org.apache.spark.rdd.RDD
import org.apache.spark.rdd.ShuffledRDDPartition
import org.apache.spark.scheduler.MapStatus
import org.apache.spark.scheduler.Task
import org.apache.tez.runtime.library.common.ValuesIterator
import org.apache.spark.shuffle.ShuffleWriter
import org.apache.spark.rdd.ShuffledRDD
import org.apache.spark.shuffle.BaseShuffleHandle
import org.apache.spark.ShuffleDependency

/**
 * Tez vertex Task modeled after Spark's ResultTask
 */
class VertexResultTask[T, U](
  stageId: Int,
  rdd: RDD[T],
  partition: Partition,
  func: (TaskContext, Iterator[T]) => U)
  extends Task[U](stageId, partition.index) with Serializable {

  /**
   *
   */
  override def runTask(context: TaskContext): U = {
    val manager = SparkEnv.get.shuffleManager

    val m = func.getClass().getDeclaredMethods().filter(m => classOf[TaskContext].isAssignableFrom(m.getParameterTypes()(0)))
    val unit = m(0).getReturnType().getCanonicalName() == "void"

    val collectFunction = (iter: Iterator[Product2[_,_]]) => {
      val dependency = rdd.dependencies.head
      val handle =
        if (dependency.isInstanceOf[ShuffleDependency[_, _, _]]) {
          new BaseShuffleHandle(0, 0, dependency.asInstanceOf[ShuffleDependency[_, _, _]])
        } else {
          null
        }
      val writer = manager.getWriter[Any, Any](handle, 0, context)
      writer.write(iter)
    }
      
    try {
      val result =
        if (unit) {
          func(context, rdd.iterator(partition, context))
        } else {
           collectFunction(rdd.iterator(partition, context).asInstanceOf[Iterator[Product2[_,_]]])
        }
      ().asInstanceOf[U]
    } catch {
      case e:Exception => e.printStackTrace();throw new IllegalStateException(e)
    } 
    finally {
      context.markTaskCompleted()
    }
  }

  /**
   *
   */
  override def toString = "ResultTask(" + stageId + ", " + partitionId + ")"
}