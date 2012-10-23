package com.github.rodrigopr.recsys.tasks

import com.github.rodrigopr.recsys.Task
import com.typesafe.config.Config
import io.Source
import scala.math._
import java.util.concurrent.atomic.AtomicLong
import com.github.rodrigopr.recsys.datasets.Rating
import com.github.rodrigopr.recsys.utils.RedisUtil._

object Recommender extends Task {
  val notRecommended = new AtomicLong
  val totalRecommended = new AtomicLong
  var userCluster: Boolean = _

  def execute(config: Config) = {
    val datasetConfig = config.getConfig("components.importer")
    val datasetParser = DatasetImporter.getDatasetParser(datasetConfig)
    val fileName = datasetConfig.getString("resource-prefix") + "/r1.test"
    val testRatings = Source.fromFile(fileName).getLines().map(datasetParser.parseRating)
    userCluster = config.getBoolean("user-cluster")

    val errors = testRatings.map(calcError()).filter(_ != -1).toList

    val errorPercentage = sqrt(errors.sum / errors.size)


    Console.println()
    Console.println("==============================================")
    Console.println("Recommendations made: " + totalRecommended.get)
    Console.println("Recommendations not made: " + notRecommended.get)
    Console.println("Error rating: " + errorPercentage + "%")

    true
  }

  def mape(predicted: Double, rating: Double): Double = abs(rating - predicted) / rating

  def rmse(predicted: Double, rating: Double): Double = pow(rating - predicted, 2)

  def calcError(errorFunc: (Double, Double) => Double = rmse)(rating: Rating): Double = {
    val neighboursKey = buildKey("neighbours", rating.userId)
    val neighbours = pool.withClient(_.zrangeWithScore(neighboursKey, 0, -1)).get.toMap.filter(_._2 > 0)

    val movieRatingKey = userCluster match  {
      case true => {
        val cluster = pool.withClient(_.get(buildKey("user", rating.userId, "cluster"))).get
        buildKey("ratings", "movie", rating.movieId, "cluster", cluster)
      }
      case false => buildKey("ratings", "movie", rating.movieId)
    }

    val movieRatings = pool.withClient(_.zrangeWithScore(movieRatingKey, 0, -1)).get.toMap

    val ratingsOfNeighbours = movieRatings.filterKeys(neighbours.contains)

    if (ratingsOfNeighbours.isEmpty) {
      Console.println("No common rating to predict: " + rating)
      notRecommended.incrementAndGet()
      -1
    } else {
      totalRecommended.incrementAndGet()
      val predictedSim = ratingsOfNeighbours.map{ case (n, v) => neighbours(n) * v }.reduce(_+_)
      val similaritySum = ratingsOfNeighbours.map{ case (n, v) => neighbours(n)}.reduce(_+_)
      var predicted = predictedSim / similaritySum
      if (predicted > 5.1)
        predicted = 5
      Console.println("Predicted " + predicted + ", expected: " + rating.rating)
      errorFunc(rating.rating, predicted)
    }
  }
}
