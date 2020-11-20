package ca.uhn.fhir.jpa.search.builder.sql;

/*-
 * #%L
 * HAPI FHIR JPA Server
 * %%
 * Copyright (C) 2014 - 2020 University Health Network
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

import java.util.List;
import java.util.regex.Pattern;

/**
 * Represents the SQL generated by this query
 */
public class GeneratedSql {
	public static final Pattern INLINE_EQ_PATTERN = Pattern.compile("=\\s*['0-9]");
	public static final Pattern INLINE_IN_PATTERN = Pattern.compile("(in|IN)\\s*\\(\\s*['0-9]");
	private final String mySql;
	private final List<Object> myBindVariables;
	private final boolean myMatchNothing;

	public GeneratedSql(boolean theMatchNothing, String theSql, List<Object> theBindVariables) {
		assert INLINE_EQ_PATTERN.matcher(theSql).find() == false : "Non-bound SQL parameter found: " + theSql;
		assert INLINE_IN_PATTERN.matcher(theSql).find() == false : "Non-bound SQL parameter found: " + theSql;

		myMatchNothing = theMatchNothing;
		mySql = theSql;
		myBindVariables = theBindVariables;
	}

	public boolean isMatchNothing() {
		return myMatchNothing;
	}

	public List<Object> getBindVariables() {
		return myBindVariables;
	}

	public String getSql() {
		return mySql;
	}
}