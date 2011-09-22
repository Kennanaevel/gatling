package com.excilys.ebi.gatling.http.processor

import com.excilys.ebi.gatling.core.processor.Processor
import com.excilys.ebi.gatling.core.provider.ProviderType._

import com.excilys.ebi.gatling.http.request.HttpPhase._

abstract class HttpProcessor(val httpPhase: HttpPhase) extends Processor {
  def getHttpPhase = httpPhase

  def getProviderType: ProviderType
}