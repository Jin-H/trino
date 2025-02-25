/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.operator;

import com.google.inject.Inject;
import io.trino.spi.Page;
import io.trino.spi.PageIndexer;
import io.trino.spi.PageIndexerFactory;
import io.trino.spi.type.Type;
import io.trino.spi.type.TypeOperators;
import io.trino.sql.gen.JoinCompiler;

import java.util.List;

import static java.util.Objects.requireNonNull;

public class GroupByHashPageIndexerFactory
        implements PageIndexerFactory
{
    private final JoinCompiler joinCompiler;
    private final TypeOperators typeOperators;

    @Inject
    public GroupByHashPageIndexerFactory(JoinCompiler joinCompiler, TypeOperators typeOperators)
    {
        this.joinCompiler = requireNonNull(joinCompiler, "joinCompiler is null");
        this.typeOperators = requireNonNull(typeOperators, "typeOperators is null");
    }

    @Override
    public PageIndexer createPageIndexer(List<Type> types)
    {
        if (types.isEmpty()) {
            return new NoHashPageIndexer();
        }
        return new GroupByHashPageIndexer(types, joinCompiler, typeOperators);
    }

    private static class NoHashPageIndexer
            implements PageIndexer
    {
        @Override
        public int[] indexPage(Page page)
        {
            return new int[page.getPositionCount()];
        }

        @Override
        public int getMaxIndex()
        {
            return 0;
        }
    }
}
