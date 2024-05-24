package org.megras.api.rest.data

data class ApiRelevanceFeedbackQuery(val positives: List<String>, val negatives: List<String>, val predicate: String, val count: Int, val distance: Distance)