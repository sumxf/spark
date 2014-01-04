/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.mllib.linalg

import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.rdd.RDD

import org.jblas.{DoubleMatrix, Singular, MatrixFunctions}

import org.apache.spark.linalg.MatrixEntry

/**
 * Top-level methods for calling Singular Value Decomposition
 * NOTE: All matrices are in 1-indexed sparse format RDD[((int, int), value)]
 */
object SVD {
/**
 * Singular Value Decomposition for Tall and Skinny matrices.
 * Given an m x n matrix A, this will compute matrices U, S, V such that
 * A = U * S * V'
 * 
 * There is no restriction on m, but we require n^2 doubles to fit in memory.
 * Further, n should be less than m.
 * 
 * The decomposition is computed by first computing A'A = V S^2 V',
 * computing svd locally on that (since n x n is small),
 * from which we recover S and V. 
 * Then we compute U via easy matrix multiplication
 * as U =  A * V * S^-1
 * 
 * Only singular vectors associated with singular values
 * greater or equal to MIN_SVALUE are recovered. If there are k
 * such values, then the dimensions of the return will be:
 *
 * S is k x k and diagonal, holding the singular values on diagonal
 * U is m x k and satisfies U'U = eye(k)
 * V is n x k and satisfies V'V = eye(k)
 *
 * All input and output is expected in sparse matrix format, 1-indexed
 * as tuples of the form ((i,j),value) all in RDDs
 *
 * @param data RDD Matrix in sparse 1-index format ((int, int), value)
 * @param m number of rows
 * @param n number of columns
 * @param min_svalue Recover singular values greater or equal to min_svalue
 * @return Three sparse matrices: U, S, V such that A = USV^T
 */
  def sparseSVD(
      data: RDD[MatrixEntry],
      m: Int,
      n: Int,
      min_svalue: Double)
    : (  RDD[MatrixEntry],
         RDD[MatrixEntry],
         RDD[MatrixEntry]) =
  {
    if (m < n || m <= 0 || n <= 0) {
      throw new IllegalArgumentException("Expecting a tall and skinny matrix")
    }

    if (min_svalue < 1.0e-8) {
      throw new IllegalArgumentException("Minimum singular value requested is too small")
    }

    // Compute A^T A, assuming rows are sparse enough to fit in memory
    val rows = data.map(entry =>
            (entry.i, (entry.j, entry.mval))).groupByKey().cache()
    val emits = rows.flatMap{ case (rowind, cols)  =>
      cols.flatMap{ case (colind1, mval1) =>
                    cols.map{ case (colind2, mval2) =>
                            ((colind1, colind2), mval1*mval2) } }
    }.reduceByKey(_+_)

    // Construct jblas A^T A locally
    val ata = DoubleMatrix.zeros(n, n)
    for(entry <- emits.toArray) {
      ata.put(entry._1._1-1, entry._1._2-1, entry._2)
    }

    // Since A^T A is small, we can compute its SVD directly
    val svd = Singular.sparseSVD(ata)
    val V = svd(0)
    val sigma = MatrixFunctions.sqrt(svd(1)).toArray.filter(x => x >= min_svalue)

    // threshold s values
    if(sigma.isEmpty) {
      throw new Exception("All singular values are smaller than min_svalue: " + min_svalue)
    }

    val sc = data.sparkContext

    // prepare V for returning
    val retV = sc.makeRDD(
            Array.tabulate(V.rows, sigma.length){ (i,j) =>
                    MatrixEntry(i+1, j+1, V.get(i,j)) }.flatten)

    val retS = sc.makeRDD(Array.tabulate(sigma.length){x=>MatrixEntry(x+1,x+1,sigma(x))})

    // Compute U as U = A V S^-1
    // turn V S^-1 into an RDD as a sparse matrix and cache it
    val vsirdd = sc.makeRDD(Array.tabulate(V.rows, sigma.length)
                { (i,j) => ((i+1, j+1), V.get(i,j)/sigma(j))  }.flatten).cache()

    // Multiply A by VS^-1
    val aCols = data.map(entry => (entry._1._2, (entry._1._1, entry._2)))
    val bRows = vsirdd.map(entry => (entry._1._1, (entry._1._2, entry._2)))
    val retU = aCols.join(bRows).map( {case (key, ( (rowInd, rowVal), (colInd, colVal)) )
        => ((rowInd, colInd), rowVal*colVal)}).reduceByKey(_+_)
          .map( case (row, col, mval) => MatrixEntry(row, col, mval))
     
    (retU, retS, retV)  
  }


  def main(args: Array[String]) {
    if (args.length < 8) {
      println("Usage: SVD <master> <matrix_file> <m> <n>
              <minimum_singular_value> <output_U_file> <output_S_file> <output_V_file>")
      System.exit(1)
    }
    val (master, inputFile, m, n, min_svalue, output_u, output_s, output_v) = 
      (args(0), args(1), args(2).toInt, args(3).toInt,
      args(4).toDouble, args(5), args(6), args(7))
    
    val sc = new SparkContext(master, "SVD")
    
    val rawdata = sc.textFile(inputFile)
    val data = rawdata.map { line =>
      val parts = line.split(',')
      ((parts(0).toInt, parts(1).toInt), parts(2).toDouble)
    }

    val (u, s, v) = SVD.sparseSVD(data, m, n, min_svalue)
    println("Computed " + s.toArray.length + " singular values and vectors")
    u.saveAsTextFile(output_u)
    s.saveAsTextFile(output_s)
    v.saveAsTextFile(output_v)
    System.exit(0)
  }
}


