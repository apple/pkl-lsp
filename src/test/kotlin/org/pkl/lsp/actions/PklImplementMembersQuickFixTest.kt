/*
 * Copyright © 2026 Apple Inc. and the Pkl project authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.pkl.lsp.actions

import org.assertj.core.api.Assertions.assertThat
import org.eclipse.lsp4j.CodeAction
import org.junit.jupiter.api.Test
import org.pkl.lsp.ErrorMessages
import org.pkl.lsp.LspTestBase
import org.pkl.lsp.VirtualFile

class PklImplementMembersQuickFixTest : LspTestBase() {
  private val quickFixTitle = ErrorMessages.create("implementMembers")

  private fun checkQuickFix(before: String, after: String) {
    createPklVirtualFile(before)
    implementMembersAndAssertResultEqualTo(after)
  }

  private fun implementMembersAndAssertResultEqualTo(expectedResult: String) {
    val file = fakeProject.virtualFileManager.get(fileInFocus!!)!!
    runAction(getAction(file))
    assertThat(file.contents).isEqualTo(expectedResult)
  }

  private fun getAction(file: VirtualFile): CodeAction {
    for (diagnostic in getDiagnostics(file)) {
      for (action in diagnostic.actions) {
        if (action.title == quickFixTitle) {
          return action.toMessage(diagnostic)
        }
      }
    }
    throw IllegalStateException("No implement members quickfix found")
  }

  @Test
  fun `quickfix implements abstract property`() {
    checkQuickFix(
      before =
        """
        abstract module Foo

        abstract class Base {
          abstract name: String
        }
        class Child extends Base {}
        """
          .trimIndent(),
      after =
        """
        abstract module Foo

        abstract class Base {
          abstract name: String
        }
        class Child extends Base {
          name: String = TODO()
        }
        """
          .trimIndent(),
    )
  }

  @Test
  fun `quickfix implements abstract method`() {
    checkQuickFix(
      before =
        """
        abstract module Foo

        abstract class Base {
          abstract function greet(): String
        }
        class Child extends Base {}
        """
          .trimIndent(),
      after =
        """
        abstract module Foo

        abstract class Base {
          abstract function greet(): String
        }
        class Child extends Base {
          function greet(): String = TODO()
        }
        """
          .trimIndent(),
    )
  }

  @Test
  fun `quickfix fills fixed property value`() {
    checkQuickFix(
      before =
        """
        abstract module Foo

        abstract class Base {
          fixed name: String
        }
        class Child extends Base {}
        """
          .trimIndent(),
      after =
        """
        abstract module Foo

        abstract class Base {
          fixed name: String
        }
        class Child extends Base {
          fixed name: String = TODO()
        }
        """
          .trimIndent(),
    )
  }

  @Test
  fun `quickfix implements abstract fixed property`() {
    checkQuickFix(
      before =
        """
        abstract module Foo

        abstract class Base {
          abstract fixed name: String
        }
        class Child extends Base {}
        """
          .trimIndent(),
      after =
        """
        abstract module Foo

        abstract class Base {
          abstract fixed name: String
        }
        class Child extends Base {
          fixed name: String = TODO()
        }
        """
          .trimIndent(),
    )
  }

  @Test
  fun `quickfix implements multiple missing members`() {
    checkQuickFix(
      before =
        """
        abstract module Foo

        abstract class Base {
          abstract name: String
          abstract function greet(): String
        }
        class Child extends Base {}
        """
          .trimIndent(),
      after =
        """
        abstract module Foo

        abstract class Base {
          abstract name: String
          abstract function greet(): String
        }
        class Child extends Base {
          name: String = TODO()

          function greet(): String = TODO()
        }
        """
          .trimIndent(),
    )
  }

  @Test
  fun `quickfix implements class members through nested hierarchy`() {
    checkQuickFix(
      before =
        """
        abstract module Foo

        abstract class Base {
          abstract name: String
          abstract function greet(): String
        }

        abstract class Base2 extends Base {}

        class Child extends Base2 {}
        """
          .trimIndent(),
      after =
        """
        abstract module Foo

        abstract class Base {
          abstract name: String
          abstract function greet(): String
        }

        abstract class Base2 extends Base {}

        class Child extends Base2 {
          name: String = TODO()

          function greet(): String = TODO()
        }
        """
          .trimIndent(),
    )
  }

  @Test
  fun `quickfix implements module members`() {
    createPklFile(
      "foo.pkl",
      """
      abstract module foo

      abstract bar: Int
      """
        .trimIndent(),
    )
    createPklFile(
      "test.pkl",
      """
      extends "foo.pkl"
      """
        .trimIndent(),
    )
    implementMembersAndAssertResultEqualTo(
      """
      extends "foo.pkl"

      bar: Int = TODO()

      """
        .trimIndent()
    )
  }

  @Test
  fun `module type in same module`() {
    checkQuickFix(
      before =
        """
        abstract module Foo

        abstract class Base {
          abstract myModule: module
        }

        class Child extends Base {}
        """
          .trimIndent(),
      after =
        """
        abstract module Foo

        abstract class Base {
          abstract myModule: module
        }

        class Child extends Base {
          myModule: module = TODO()
        }
        """
          .trimIndent(),
    )
  }

  @Test
  fun `import type in different module`() {
    createPklFile(
      "foo.pkl",
      """
      abstract module foo

      import "Bar.pkl"

      abstract class Base {
        abstract bar: Bar
      }
      """
        .trimIndent(),
    )

    createPklFile("Bar.pkl", "")
    createPklFile(
      "test.pkl",
      """
      import "foo.pkl"

      class Child extends foo.Base
      """
        .trimIndent(),
    )
    implementMembersAndAssertResultEqualTo(
      """
      import "Bar.pkl"
      import "foo.pkl"

      class Child extends foo.Base {
        bar: Bar = TODO()
      }
      """
        .trimIndent()
    )
  }

  @Test
  fun `import nested type in different module`() {
    createPklFile(
      "foo.pkl",
      """
      abstract module foo

      import "bar.pkl"

      abstract class Base {
        abstract bar: bar.Qux
      }
      """
        .trimIndent(),
    )

    createPklFile(
      "bar.pkl",
      """
      class Qux
      """
        .trimIndent(),
    )
    createPklFile(
      "test.pkl",
      """
      import "foo.pkl"

      class Child extends foo.Base
      """
        .trimIndent(),
    )
    implementMembersAndAssertResultEqualTo(
      """
      import "bar.pkl"
      import "foo.pkl"

      class Child extends foo.Base {
        bar: bar.Qux = TODO()
      }
      """
        .trimIndent()
    )
  }

  @Test
  fun `import type in different module, with conflicts`() {
    createPklFile(
      "foo.pkl",
      """
      abstract module foo

      import "bar.pkl"

      abstract class Base {
        abstract bar: bar.Qux
      }
      """
        .trimIndent(),
    )

    createPklFile(
      "bar.pkl",
      """
      class Qux
      """
        .trimIndent(),
    )

    createPklFile(
      "test.pkl",
      """
      import "foo.pkl"
      import "other.pkl" as bar

      class Child extends foo.Base
      """
        .trimIndent(),
    )
    implementMembersAndAssertResultEqualTo(
      """
      import "bar.pkl" as bar2
      import "foo.pkl"
      import "other.pkl" as bar

      class Child extends foo.Base {
        bar: bar2.Qux = TODO()
      }
      """
        .trimIndent()
    )
  }

  @Test
  fun `module type in different module`() {
    createPklFile(
      "foo.pkl",
      """
      abstract module foo

      import "Bar.pkl"

      abstract class Base extends Bar
      """
        .trimIndent(),
    )

    createPklFile(
      "Bar.pkl",
      """
      abstract foo: module
      """
        .trimIndent(),
    )

    createPklFile(
      "test.pkl",
      """
      import "foo.pkl"

      class Child extends foo.Base
      """
        .trimIndent(),
    )
    implementMembersAndAssertResultEqualTo(
      """
      import "Bar.pkl"
      import "foo.pkl"

      class Child extends foo.Base {
        foo: Bar = TODO()
      }
      """
        .trimIndent()
    )
  }

  @Test
  fun `class with existing member`() {
    checkQuickFix(
      before =
        """
        abstract module Foo
        abstract class Base {
          abstract name: String
        }
        class Child extends Base {
          bar: Int = 5
        }
        """
          .trimIndent(),
      after =
        """
        abstract module Foo
        abstract class Base {
          abstract name: String
        }
        class Child extends Base {
          bar: Int = 5

          name: String = TODO()
        }
        """
          .trimIndent(),
    )
  }

  @Test
  fun `quickfix does not reimplement fixed property that has an inherited default`() {
    checkQuickFix(
      before =
        """
        abstract module Foo

        abstract class Base {
          fixed name: String = "default"
          abstract function greet(): String
        }

        class Child extends Base {}
        """
          .trimIndent(),
      after =
        """
        abstract module Foo

        abstract class Base {
          fixed name: String = "default"
          abstract function greet(): String
        }

        class Child extends Base {
          function greet(): String = TODO()
        }
        """
          .trimIndent(),
    )
  }

  @Test
  fun `import type in different module, with chained alias conflicts`() {
    createPklFile(
      "foo.pkl",
      """
      abstract module foo
      import "bar.pkl"

      abstract class Base {
        abstract bar: bar.Qux
      }
      """
        .trimIndent(),
    )
    createPklFile(
      "bar.pkl",
      """
      class Qux
      """
        .trimIndent(),
    )
    createPklFile(
      "test.pkl",
      """
      import "aaa.pkl" as bar2
      import "foo.pkl"
      import "other.pkl" as bar

      class Child extends foo.Base
      """
        .trimIndent(),
    )
    implementMembersAndAssertResultEqualTo(
      """
      import "aaa.pkl" as bar2
      import "bar.pkl" as bar3
      import "foo.pkl"
      import "other.pkl" as bar

      class Child extends foo.Base {
        bar: bar3.Qux = TODO()
      }
      """
        .trimIndent()
    )
  }
}
