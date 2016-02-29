package lasso

import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.SparkConf
import org.apache.spark.broadcast.Broadcast

import scala.util.control.Breaks._

import breeze.linalg.{DenseVector => BDV, SparseVector => BSV, Vector => BV, norm}
import breeze.linalg.{DenseMatrix => BDM}

import org.apache.log4j.{Logger, Level}

import org.apache.spark.rdd.RDD
import org.apache.spark.HashPartitioner
import org.apache.spark.Logging


case class BlockSize(nrows: Int, ncols: Int)

class ColumnVector(
  val size: Int,                        // Size of a block
  val nblocks: Int,
  val vec: RDD[(Int, BDV[Double])]) 
{
  def norm(power: Int = 2): Double = {
    val n = vec.mapValues{x => math.pow(x.norm(),power)}
    val sum = n.values.treeReduce(_ + _)
    sum
  }

  def frob(): Double = {
    val n = this.norm()
    val tot = math.sqrt(n)
    tot
  }

  def +(other: BDV[Double]): ColumnVector = {
    val u = vec.mapValues{x => x + other} 
    new ColumnVector(size,nblocks,u)
  }

  def -(other: BDV[Double]): ColumnVector = {
    val u = vec.mapValues{x => x - other}
    new ColumnVector(size,nblocks,u)
  }

  def -(that: ColumnVector): ColumnVector = {
    val u = vec.join(that.vec).mapValues{ case (x, y) => x - y}
    new ColumnVector(size,nblocks,u)
  }

  def *(scalar: Double): ColumnVector = {
    val u = vec.mapValues{x => x :* scalar}
    new ColumnVector(size,nblocks,u)
  }
  
  def copy: ColumnVector = {
    new ColumnVector(size, nblocks, vec)
  } 

  def cache(): this.type = {
    vec.cache()
    this
  }
}

object ColumnVector {

  def fromTextFile(
    sc: SparkContext, 
    fin: String,
    nblocks: Int,
    delim: String = " "): ColumnVector = {
    val lines = sc.textFile(fin, nblocks).map{ x => 
      val nums = x.split(delim)
      (nums(0).toInt, nums(1).toDouble)}
    val size = lines.count().toInt

    val groupLines = lines.map{ case (x, y) => 
      val group = math.floor(x / nblocks.toDouble).toInt
      (group, (x, y))}.groupByKey

    groupLines.partitionBy(new HashPartitioner(nblocks))

    val blockSize = math.ceil(size.toDouble / nblocks).toInt

    val vec_rdd = groupLines.mapValues{ it => 
      val v = BDV.zeros[Double](blockSize)
      val stream = it.iterator
      var k = 0
      while(stream.hasNext) {
        val current = stream.next()
        v(k) = current._2
        k += 1
      }
      v
    }
    new ColumnVector(size, nblocks, vec_rdd)
  }

  def zeros(sc: SparkContext, nblocks: Int, size: Int): ColumnVector = {
    val v = sc.parallelize(0 to nblocks - 1, nblocks)
            .map{ i => (i, BDV.zeros[Double](size))}
    new ColumnVector(size, nblocks, v)
  }

}

class ColumnMatrix(
  val size: BlockSize,
  val nblocks: Int,
  val mat: RDD[(Int, BDM[Double])])
{
  def cache(): this.type = {
    mat.cache()
    this
  }

  def multiply(x: ColumnVector): BDV[Double] = {
    parallelMultiply(x).vec.values.treeReduce(_ + _)
  }

  def +(M: BDM[Double]): ColumnMatrix = {
    val u = mat.mapValues{x => x + M}
    new ColumnMatrix(size, nblocks, u)
  }    

  def parallelMultiply(x: ColumnVector): ColumnVector = {
    val b: RDD[(Int,BDV[Double])] = mat 
      .join(x.vec)
      .mapValues{ x => x._1 * x._2}
    new ColumnVector(size.nrows, x.nblocks, b)
  }

  def parallelMultiply(vec: BDV[Double]): ColumnVector = {
    val b: RDD[(Int, BDV[Double])] = mat
      .mapValues{ x => x * vec}
    new ColumnVector(size.ncols * nblocks, nblocks, b) 
  }

  def gram(): ColumnMatrix = {
    val gram_size = BlockSize(size.ncols,size.ncols)
    val blocks = mat.mapValues{a => a.t * a}
    new ColumnMatrix(gram_size,nblocks,blocks)
  }

  def transpose(): ColumnMatrix = {
    /*NOTE: This function produces a form of matrix incompatible with the multiply and
    parallelMultiply functions. This may be a problem*/
    val u = mat.mapValues{a => a.t}
    val newSize = BlockSize(size.ncols, size.nrows)
    new ColumnMatrix(newSize,nblocks,u)
  }

  def solve(b: ColumnVector): ColumnVector = {
    val u = mat.join(b.vec).mapValues{ case (x, b) => x \ b}
    new ColumnVector(b.size, b.nblocks, u)
  }
}

object ColumnMatrix {

  def fromTextFile(
    sc: SparkContext,
    fin: String, 
    nblocks: Int, 
    delim: String = " "): ColumnMatrix = {
    val lines = sc.textFile(fin, nblocks).map{ x => 
      val nums = x.split(delim)
      val elements = nums.tail.map(x => x.toDouble)
      (nums(0).toInt, elements)}

    val ncols = (lines.count() / nblocks).toInt
    val nrows = lines.first()._2.length
    val size = BlockSize(nrows, ncols)

    val groupLines = lines.map{ case (x, y) => 
      val group = math.floor(x / ncols.toDouble).toInt
      (group, (x, y))}.groupByKey
    groupLines.partitionBy(new HashPartitioner(nblocks)) 
    val mat_rdd = groupLines.mapValues{ it => 
      val stream = it.iterator
      var k = 0
      val mat = BDM.zeros[Double](size.nrows, size.ncols)
      while(stream.hasNext) {
        val current = stream.next()
        val v = BDV(current._2)
        mat(::, k) := v
        k += 1
      }
      mat
    }
    new ColumnMatrix(size, nblocks, mat_rdd)
  }
}


object Lasso extends Logging{

  private def logStdout(msg: String): Unit = {
    val time: Long = System.currentTimeMillis;
//    logInfo(msg);
    println(time + ": " + msg);
  }

  private type ArgMap = Map[String,String]
  private def argToMap(keyName: String, value: String): ArgMap = {
    logStdout(keyName + " flag set to " + value);
    Map(keyName -> value)
  }

  private def parseArgs(map: ArgMap, list: List[String]) : ArgMap = 
  {
    list match 
    {
      case Nil => map
      case ("--rho") :: value :: tail => {
	parseArgs(map ++ argToMap("rho", value), tail)
      }
      case ("--lambda") :: value :: tail => {
	parseArgs(map ++ argToMap("lambda", value), tail)
      }
      case ("--vector") :: value :: tail => {
	parseArgs(map ++ argToMap("vector", value), tail)
      }
      case ("--alpha") :: value :: tail => {
        parseArgs(map ++ argToMap("alpha", value), tail)
      }
      case ("--matrix") :: value :: tail => {
        parseArgs(map ++ argToMap("matrix", value), tail)
      }
      case ("--partitions") :: value :: tail => {
        parseArgs(map ++ argToMap("partitions", value), tail)
      }
      case other :: tail => {
	logStdout("Read non-flag value: " + other)
	parseArgs(map, tail)
      }
    }
  }

  def main(args: Array[String]) 
  {
    Logger.getLogger("org").setLevel(Level.OFF)
    Logger.getLogger("akka").setLevel(Level.OFF)
    val conf = new SparkConf().setAppName("Simple Test").setMaster("local")
    val sc = new SparkContext(conf)

    try {
      val vars: ArgMap = parseArgs(Map(),args.toList);
      val rho = if (vars.contains("rho")) vars("rho").toDouble else 10.0
      val lambda = if (vars.contains("lambda")) vars("lambda").toDouble else 1.95986
      val nblocks = if (vars.contains("partitions")) vars("partitions").toInt else 3
      val alpha = if (vars.contains("alpha")) vars("alpha").toInt else 1.0
      val matrix_fid = if (vars.contains("matrix")) vars("matrix") else "mat_test_data.txt"
      val vector_fid = if (vars.contains("vector")) vars("vector") else "vec_test_data.txt"

      val A = ColumnMatrix.fromTextFile(sc, matrix_fid, nblocks).cache()
      val b = BDV(sc.textFile(vector_fid).collect().map{x => x.toDouble})

      lasso(sc, A, b, lambda, rho, alpha)
    }
    finally {
      sc.stop
    }
  }

  def shrink(
    At: ColumnMatrix,
    b: ColumnVector,
    x: ColumnVector, 
    gram: ColumnMatrix,
    mu: Double): ColumnVector = {

    val (n, m) = (At.size.nrows, At.size.ncols)
    val q: ColumnVector = At.parallelMultiply(b)
    
    val zerox = x.vec.join(q.vec).mapValues{ case (x, q) =>
      if (math.sqrt(q.norm()) < mu) {
        (BDV.zeros[Double](n), q)
      } else (x, q)}

    val shrunkx = zerox.join(gram.mat).mapValues{ case ((x, q), g) => 
      var (lower, upper) = (0.0, 1e6)
      var k = 0;
      var xHat = BDV.zeros[Double](1)                                                  
      breakable {while (k < 100) {
        val nu = (upper + lower) / 2
        xHat = (g + BDM.eye[Double](g.rows) * nu) \ q
         
        if (nu > (mu / xHat.norm())) {
          upper = nu
        } else {
          lower = nu
        }
        if (upper - lower <= 1e-6) {
          break
        }
        k += 1
      }}
      (xHat)
    }
    new ColumnVector(x.size, x.nblocks, shrunkx)
  }

  def objective(
    b: BDV[Double],
    lambda: Double,
    N: Int,
    x: ColumnVector,
    z: BDV[Double]): Double = {
    val p = .5 * math.pow(((z*N.toDouble) - b).norm(), 2) + lambda * x.norm(1)
    p
  }

  def lasso(
    sc: SparkContext,
    A: ColumnMatrix,
    b: BDV[Double], 
    lambda: Double, 
    rho: Double, 
    alpha: Double, 
    maxIters: Int = 100,
    absTol: Double = 1e-4,
    relTol: Double = 1e-2,
    quiet: Boolean = false): ColumnVector = {
    
    // Perform Data preprocessing
    val N = A.nblocks                   // Number of partitions
    val m = A.size.nrows
    val ni = A.size.ncols               // Size of each of the partitions
    val n = N * ni                      // Total number of columns
    val mu =  lambda / rho.toDouble
    
    // Initialise Vectors
    var u = BDV.zeros[Double](m)
    var Axbar = BDV.zeros[Double](m)
    var z = BDV.zeros[Double](m)
    var Aixi = ColumnVector.zeros(sc, N, m) 
    var zs = ColumnVector.zeros(sc, N, m)
    var AxbarHat = BDV.zeros[Double](1)
    val AtA = A.gram.cache
    val At = A.transpose.cache
    val (rNorm, epsPri, sNorm, epsDual) = (1.0, 0.0, 1.0, 0.0)

    // Create the RDD for x, initialised to zeros. 
    var x = ColumnVector.zeros(sc, N, ni) 
    var k = 0
    logStdout("iter \t r norm \t eps pri \t s norm \t eps dual \t objective")

    while ((k < maxIters) || ((rNorm > epsPri) && (sNorm > epsDual))) {
      // x update
      val v = ((Aixi + z) - Axbar) - u
      x = shrink(At, v, x, AtA, mu).cache()
      Aixi = A.parallelMultiply(x).cache()

      // z update
      val zold = z.copy
      Axbar = A.multiply(x) / N.toDouble
      AxbarHat = (Axbar * alpha) + zold * (1-alpha)
      z = (b + (AxbarHat + u)*rho)/(N+rho).toDouble
      
      // u update
      u = u + AxbarHat - z

      // compute the dual residual norm square
      val zsold = zs.copy
      zs = ((Aixi + z) - Axbar).cache()

      val deltaz = zs - zsold
      val s = ((At.parallelMultiply(deltaz)) * -rho).norm()
      val q = (At.parallelMultiply(u) * rho).norm()

      // update history and perform termination checks
      k += 1

      val objval = objective(b, lambda, N, x, z)
      val rNorm = math.sqrt(N) * (z - Axbar).norm()
      val sNorm = math.sqrt(s)
      
      val epsPri = math.sqrt(n)*absTol + relTol * math.max(Aixi.frob(), zs.frob())
      val epsDual = math.sqrt(n)*absTol + relTol*math.sqrt(q)
      
      if(!quiet) {
        logStdout(s"$k: ${rNorm}: ${epsPri}: ${sNorm}: ${epsDual}: ${objval}")
      }
    }
    x
  }
}

// object Lasso {

//   def main(args: Array[String]) {
//     Logger.getLogger("org").setLevel(Level.OFF)
//     Logger.getLogger("akka").setLevel(Level.OFF)
//     val sc = new SparkContext("local", "Lasso")

//     try {
//       val A = ColumnMatrix.fromTextFile(sc, "test_mat.txt", 2)
//       val N = 2
//       val m = 2
//       val ni = 2
//       val x = ColumnVector.zeros(sc, N, ni)
//       val alpha = 0.5
//       val Axbar = sc.broadcast(A.multiply(x))
//       val z = sc.broadcast(BDV.zeros[Double](m))


//       val zold = z.value 
//       val AxbarHat = (Axbar.value * alpha) + zold * (1.0 - alpha)
//       z.unpersist()
//       val z = sc.broadcast(((AxbarHat + u.value)*alpha)/(N).toDouble)
      
      
//     }
//     finally { 
//       sc.stop
//     }
//   }
// }
