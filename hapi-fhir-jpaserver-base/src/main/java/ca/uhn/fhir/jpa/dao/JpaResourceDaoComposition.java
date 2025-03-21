/*
 * #%L
 * HAPI FHIR JPA Server
 * %%
 * Copyright (C) 2014 - 2025 Smile CDR, Inc.
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package ca.uhn.fhir.jpa.dao;

import ca.uhn.fhir.jpa.api.dao.IFhirResourceDaoComposition;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.model.api.IResource;
import ca.uhn.fhir.rest.api.SortSpec;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.param.DateRangeParam;
import ca.uhn.fhir.rest.param.StringParam;
import jakarta.servlet.http.HttpServletRequest;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.instance.model.api.IPrimitiveType;

import java.util.Collections;

public class JpaResourceDaoComposition<T extends IBaseResource> extends BaseHapiFhirResourceDao<T>
		implements IFhirResourceDaoComposition<T> {

	@Override
	public IBundleProvider getDocumentForComposition(
			HttpServletRequest theServletRequest,
			IIdType theId,
			IPrimitiveType<Integer> theCount,
			IPrimitiveType<Integer> theOffset,
			DateRangeParam theLastUpdate,
			SortSpec theSort,
			RequestDetails theRequestDetails) {
		SearchParameterMap paramMap = new SearchParameterMap();
		if (theCount != null) {
			paramMap.setCount(theCount.getValue());
		}
		if (theOffset != null) {
			paramMap.setOffset(theOffset.getValue());
		}
		paramMap.setIncludes(Collections.singleton(IResource.INCLUDE_ALL.asRecursive()));
		paramMap.setSort(theSort);
		paramMap.setLastUpdated(theLastUpdate);
		if (theId != null) {
			paramMap.add("_id", new StringParam(theId.getIdPart()));
		}
		return search(paramMap, theRequestDetails);
	}
}
