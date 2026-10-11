package dev.dojo.fallback.classification.client;

import dev.dojo.fallback.classification.model.ClassificationAnswer;
import dev.dojo.fallback.error.ErrorLog;

public interface Classifier {
    ClassificationAnswer classify(ErrorLog error);
}
