package com.github.rodrigopr.recsys.tasks

import scala.math._
import com.github.rodrigopr.recsys.{DataStore, StatsHolder, Task}
import com.typesafe.config.Config
import com.github.rodrigopr.recsys.datasets.Neighbour

case class CommonRating(userId1: String, userId2: String, candidate: String, uRating: Double, oRating: Double)

object NeighborSelection extends Task {
  private var useCluster: Boolean = _
  private var numNeighbors: Int = _

  def execute(config: Config) = {
    useCluster = Option(config getBoolean "user-cluster") getOrElse false
    numNeighbors = Option(config getInt "num-neighbours") getOrElse 20

    DataStore.users.keySet.foreach( user =>  StatsHolder.timeIt("NeighborSel-User", print = true) {
      val neighbors = getUserNeighbours(user, numNeighbors)

      DataStore.userNeighbours.put(user, neighbors)
    })

    Console.println("Finish processing users")
    Console.println("Processing movies")

    val itemBased = config.getBoolean("item-based")

    if(itemBased) {
      DataStore.movies.keySet.foreach( movie => StatsHolder.timeIt("NeighborSel-Item", print = true) {
        val neighbors = getItemNeighbours(movie, numNeighbors)
        DataStore.movieNeighbours.put(movie, neighbors)
      })
    }

    true
  }

  def pearsonSimilarity(ratingsInCommon: Iterable[CommonRating]): Double = {
    if(ratingsInCommon.size <= 2) {
      return 0.0
    }

    var user1SumSquare = 0.0d
    var user2SumSquare = 0.0d
    var sumSquare = 0.0d

    var commonRatingsTotal = 0

    ratingsInCommon.foreach{ case CommonRating(userId1, userId2, _, myRating, otherRating) =>
      commonRatingsTotal = commonRatingsTotal + 1

      // Sum the squares
      user1SumSquare = user1SumSquare + pow(myRating - DataStore.avgRatingUser(userId1), 2.0)
      user2SumSquare = user2SumSquare + pow(otherRating - DataStore.avgRatingUser(userId2), 2.0)

      // Sum the products
      sumSquare = sumSquare + ((myRating - DataStore.avgRatingUser(userId1)) * (otherRating - DataStore.avgRatingUser(userId2)))
    }

    // Calculate Pearson Correlation score
    val numerator = sumSquare
    val denominator = (sqrt(user1SumSquare) * sqrt(user2SumSquare))

    denominator match {
      case 0 => 0
      case _ => {
        val res = (1.0 + (numerator / denominator)) / 2.0

        res * min(commonRatingsTotal/50.0, 1)
      }
    }
  }

  def getUserNeighbours(user: String, numNeighbours: Long): List[Neighbour] = {
    if(!DataStore.userRatings.contains(user)) {
      return List()
    }

    val cluster = DataStore.userCluster(user)
    val myRatings = DataStore.userRatings(user)

    val users: Iterator[String] = if(useCluster) DataStore.clusters(cluster).toIterator else DataStore.users.keysIterator

    val neighbours = users.filterNot(user.eq).filter(DataStore.userRatings.contains).map { oUserId =>
      val commonItems = DataStore.userRatings(oUserId).view.filter(m => myRatings.contains(m._1))

      val commonRatings = commonItems.map{ case(movieId, oRating) =>
        CommonRating(user, oUserId, oUserId, myRatings.getOrElse(movieId, 0.0), oRating)
      }.toIterable

      Neighbour(oUserId, pearsonSimilarity(commonRatings))
    }.toList

    neighbours.sortBy(n => n.similarity).takeRight(numNeighbors)
  }

  def getItemNeighbours(movie: String, numNeighbours: Long): List[Neighbour] = {
    if (!DataStore.movieRatings.contains(movie) || DataStore.movieRatings(movie).isEmpty) {
      return List()
    }

    val myRatings = DataStore.movieRatings(movie)

    val candidates = myRatings.keySet.map( user => DataStore.userRatings(user).keySet ).flatten.filterNot(movie.eq)

    val neighbours = candidates.map { oMovieId =>
      val oRatings = DataStore.movieRatings(oMovieId)
      val commonRatings = myRatings.keysIterator.filter(oRatings.contains).map{ user =>
        CommonRating(user, user, oMovieId, myRatings.getOrElse(user, 0.0), oRatings.getOrElse(user, 0.0))
      }.toIterable

      Neighbour(oMovieId, pearsonSimilarity(commonRatings))
    }.toList

    neighbours.sortBy(n => n.similarity).takeRight(numNeighbors)
  }
}
