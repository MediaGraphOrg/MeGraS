package org.megras.api.rest.data

data class ApiRelevanceFeedbackQuery(val positives: List<ApiQuad>, val negatives: List<ApiQuad>)