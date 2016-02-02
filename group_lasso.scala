import scala.math
import org.apache.spark.mllib.linalg.distributed.{IndexedRow, IndexedRowMatrix, RowMatrix}
import org.apache.spark.mllib.linalg.{Vector, Vectors, BLAS}

import breeze.linalg.{DenseVector => BDV, SparseVector => BSV, Vector => BV}
import breeze.linalg.{DenseMatrix => BDM}

import org.apache.log4j.Logger

import org.apache.spark.rdd.RDD

/*type BlockMat = BDM[Double]*/
/*type BlockVec = BDV[Double]*/
case class BlockSize(nrows: Int, ncols: Int)

class ColumnVector(
  val size: BlockSize,
  val nblocks: Int,
  val vec: RDD[(Int, BDV[Double])])
{
  def norm(p: Int = 2): Double = {
  }
  def +(other: BDV[Double]) = {
    val u = vec.map{x => x + other} 
    ColumnVector(size,nblocks,u)
  }
}

object ColumnVector {
  // read a text file into a Column matrix with nblocks
  def fromTextfile(sc: SparkContext, fin: String, nblocks: Int): ColumnVector = {
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

  def parallelMultiply(x: ColumnVector): ColumnVector = {
    val b: RDD[(Int,BDV[Double])] = mat 
      .join(x.vec)
      .mapValues{ case (A_i,x_i) => A_i.multiply(x_i) }
    ColumnVector(x.size, x.nblocks, b)
  }
  def gram(): ColumnMatrix = {
    val gram_size = BlockSize(size.ncols,size.ncols)
    val blocks = mat.mapValues{a => a.t * a}
    ColumnMatrix(gram_size,nblocks,blocks)
  }
  def solve(b: ColumnVector, nu: Double): ColumnVector = 
  {
    def solve_system(A: BDM[Double], v: BDV[Double]): BDV[Double] = 
    {
      val M = // A.t*A + nu * I
      // call blas routine for solving linear system

    }
    mat.join(v.vec).map{solve_system}
  }
}
object ColumnMatrix {
  // read a text file into a Column matrix with nblocks
  def fromTextfile(sc: SparkContext, fin: String, delim: String, nblocks: Int): ColumnMatrix = {
    val lines = RDD[String] = sc.textFile(fin)
  }

  // skeleton code
  /*def textFileSparse(sc: SparkContext, fin: String, delim: String, rowsPerBlock: Int, nrows: Int): BlockVector =*/
  /*{*/
  /*  type Entry = (Long, Double)*/

  /*  def lineToCoords(line: String): Entry = { */
  /*    val values = line.split(delim)*/
  /*    (values(0).toLong, values(1).toDouble)*/
  /*  }   */

  /*  val nblocks = (nrows / rowsPerBlock).toInt*/
  /*  val lines = sc.textFile(fin, nblocks)*/
  /*  val coords = lines.map(lineToCoords).groupByKey*/
  /*}*/
}

object Lasso extends Logging {
	private def logStdout(msg: String): Unit = {
		val time: Long = System.currentTimeMillis;
		logInfo(msg);
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
				parseArgs(map ++ argToMap("rho", value), tail);
      }
      case ("--lambda") :: value :: tail => {
				parseArgs(map ++ argToMap("lambda", value), tail);
      }
			case other :: tail => {
				logStdout("Read non-flag value: " + other);
				parseArgs(map, tail);
			}
		}
	}

  def main(args: Array[String]) 
  {
    val conf = new SparkConf()
    val sc = new SparkContext(conf)
		val vars: ArgMap = parseArgs(Map(),args.toList);

    val rho = if (vars.contains("rho")) vars("rho").toDouble else 1e-3
    val lambda = if (vars.contains("lambda")) vars("lambda").toDouble else 1e-3

    // make A matrix or read A matrix from file
    // make A matrix or read A matrix from file
    lasso(A, b, lambda, rho, alpha)  {
  }

  def x_update(): BDV = {
    // Perform the x update step
  }


  /*def init_history(): Map[String, Seq[Double]] = {*/
  /*  // Initialises a history data structure to store relevant iteration values*/
  /*  val history = map(*/
  /*    "objval" -> seq(),*/
  /*    "r_norm" -> seq(), */
  /*    "s_norm" -> seq(),*/
  /*    "eps_pri" -> seq(),*/
  /*    "eps_dual" -> seq()*/
  /*  )*/
  /*  history*/
  /*}*/


  def toBreeze(values: Array[Double]): BV[Double] = new BDV[Double](values)

  def shrink(
    A: ColumnMatrix,
    v: ColumnVector,
    rho: Double,
    lambda: Double): ColumnVector = 
  {
    val AtA = A.gram.cache
    while (bisection)
    {
      nu = ...
      x = AtA.solve(nu,v)
    }
  }

  def lasso(
    A: ColumnMatrix,
    b: BDV, lambda: Double, 
    rho: Double, 
    alpha: Double, 
    maxIters: Int = 100,
    tol: Double = 1e-4): BDV = {
    // Overall wrapper function for group lasso
    
    val QUIET = false
    val MAX_ITER = 100
    val ABSTOL = 1e-4
    val RELTOL = 1e-2

    // Perform Data preprocessing
    val N = A.partitions.size             // Number of partitions
    val m = A.first().rows
    val ni = A.first().cols               // Size of each of the partitions
    val n = N * ni                        // Total number of columns

    
    // Initialise Vectors
    var u = BDV.zeros[Double](m)
    val z = BDV.zeros[Double](m)
    val Axbar = BDV.zeros[Double](m)

    // Create the RDD for x, initialised to zeros. 

    var k = 0
    while (k < maxIters) {
      // broadcast 
      val b_bar = sc.broadcast(A.multiply(x) / n)
      // x update

      // z update
      
      // u update
      u = u + Axbar_hat + z

      // compute the dual residual norm 

      // update history and perform termination checks
      k += 1
      logStdout(s"lasso: $k: ${b_bar.norm}: ${x.norm}")
    }


  }
}
