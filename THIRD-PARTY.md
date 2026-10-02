# Third-Party Notices & Licenses

This document contains licensing notices and acknowledgements for third-party software components bundled into, integrated with, or utilized by **ST Mobile**.

---

## Disclaimer of Non-Affiliation

**ST Mobile is an independent project and is NOT affiliated with, endorsed by, sponsored by, or officially associated with the SillyTavern project, its maintainers, or contributors.** 

"SillyTavern" and any associated trademarks or names are used solely for factual identification of the underlying software bundled and executed on Android by this application.

---

## Bundled Software & Dependencies

### 1. SillyTavern
- **Project**: SillyTavern
- **Repository**: [https://github.com/SillyTavern/SillyTavern](https://github.com/SillyTavern/SillyTavern)
- **License**: GNU Affero General Public License v3.0 (AGPL-3.0)
- **Pinned Release**: `1.19.0` (commit `7e8663cd9c184a550b37238218bdd32c6efc68e9`)
- **Corresponding Source Disclosure**:
  In accordance with Sections 6 and 13 of the GNU Affero General Public License v3.0, the complete corresponding source code for the bundled SillyTavern runtime, packaging scripts, and the host ST Mobile Android application is openly published and available under the AGPL-3.0. Full license terms can be viewed in [LICENSE](LICENSE).

---

### 2. Node.js Mobile Runtime
- **Project**: `nodejs-mobile` / Node.js
- **Runtime Version**: Node.js `v24.20.0` (`v24.20.0-android.2` binary build)
- **Source Repositories**:
  - Upstream Node.js: [https://github.com/nodejs/node](https://github.com/nodejs/node)
  - Upstream nodejs-mobile: [https://github.com/nodejs-mobile/nodejs-mobile](https://github.com/nodejs-mobile/nodejs-mobile)
  - Android Build Fork: [https://github.com/FongMi/nodejs-mobile](https://github.com/FongMi/nodejs-mobile)
- **License**: MIT License
- **Copyright**:
  - Copyright Node.js contributors. All rights reserved.
  - Copyright (c) 2017-2020 Janea Systems Ltd.
  - Copyright (c) 2021-present nodejs-mobile and community contributors.

---

### 3. LLVM libc++ (`libc++_shared.so`)
- **Project**: LLVM Project / Android NDK
- **Repository**: [https://github.com/llvm/llvm-project](https://github.com/llvm/llvm-project)
- **License**: Apache License v2.0 with LLVM Exceptions
- **Copyright**: Copyright (c) 2003-2024 LLVM Team / University of Illinois at Urbana-Champaign.

---

### 4. Zip4j
- **Project**: Zip4j (Standard and AES-256 ZIP archive library)
- **Artifact**: `net.lingala.zip4j:zip4j:2.11.5`
- **Repository**: [https://github.com/srikanth-lingala/zip4j](https://github.com/srikanth-lingala/zip4j)
- **License**: Apache License v2.0
- **Copyright**: Copyright 2010-present Srikanth Reddy Lingala.

---

### 5. SnakeYAML
- **Project**: SnakeYAML
- **Artifact**: `org.yaml:snakeyaml:2.2`
- **Repository**: [https://bitbucket.org/snakeyaml/snakeyaml](https://bitbucket.org/snakeyaml/snakeyaml)
- **License**: Apache License v2.0
- **Copyright**: Copyright (c) 2008-present Andrey Somov, SnakeYAML Project.

---

### 6. Apache Commons Compress
- **Project**: Apache Commons Compress
- **Artifact**: `org.apache.commons:commons-compress:1.26.2`
- **Repository**: [https://commons.apache.org/proper/commons-compress/](https://commons.apache.org/proper/commons-compress/)
- **License**: Apache License v2.0
- **Copyright**: Copyright 2002-2024 The Apache Software Foundation.

---

### 7. AndroidX & Jetpack Compose
- **Components**:
  - `androidx.compose:compose-bom:2024.09.03`
  - `androidx.activity:activity-compose:1.9.2`
  - `androidx.compose.ui:ui`
  - `androidx.compose.foundation:foundation`
  - `androidx.compose.material3:material3`
  - `androidx.compose.material:material-icons-core`
  - `androidx.compose.material:material-icons-extended`
  - `androidx.core:core-ktx:1.13.1`
- **Repository**: [https://android.googlesource.com/platform/frameworks/support](https://android.googlesource.com/platform/frameworks/support)
- **License**: Apache License v2.0
- **Copyright**: Copyright (c) 2018-present The Android Open Source Project.

---

### 8. Test Dependencies
- **JUnit 4** (`junit:junit:4.13.2`): Eclipse Public License 1.0 (EPL-1.0)
- **JSON in Java** (`org.json:json:20240303`): Public Domain / JSON License

---

## License Texts

### MIT License

```
Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

### Apache License, Version 2.0

```
                              Apache License
                        Version 2.0, January 2004
                     http://www.apache.org/licenses/

TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION

1. Definitions.

   "License" shall mean the terms and conditions for use, reproduction,
   and distribution as defined by Sections 1 through 9 of this document.

   "Licensor" shall mean the copyright owner or entity authorized by
   the copyright owner that is granting the License.

   "Legal Entity" shall mean the union of the acting entity and all
   other entities that control, are controlled by, or are under common
   control with that entity. For the purposes of this definition,
   "control" means (i) the power, direct or indirect, to cause the
   direction or management of such entity, whether by contract or
   otherwise, or (ii) ownership of fifty percent (50%) or more of the
   outstanding shares, or (iii) beneficial ownership of such entity.

   "You" (or "Your") shall mean an individual or Legal Entity
   exercising permissions granted by this License.

   "Source" form shall mean the preferred form for making modifications,
   including but not limited to software source code, documentation
   source, and configuration files.

   "Object" form shall mean any form resulting from mechanical
   transformation or translation of a Source form, including but
   not limited to compiled object code, generated documentation,
   and conversions to other media types.

   "Work" shall mean the work of authorship, whether in Source or
   Object form, made available under the License, as indicated by a
   copyright notice that is included in or attached to the work
   (an example is provided in the Appendix below).

   "Derivative Works" shall mean any work, whether in Source or Object
   form, that is based on (or derived from) the Work and for which the
   editorial revisions, annotations, elaborations, or other modifications
   represent, as a whole, an original work of authorship. For the purposes
   of this License, Derivative Works shall not include works that remain
   separable from, or merely link (or bind by name) to the interfaces of,
   the Work and Derivative Works thereof.

   "Contribution" shall mean any work of authorship, including
   the original version of the Work and any modifications or additions
   to that Work or Derivative Works thereof, that is intentionally
   submitted to Licensor for inclusion in the Work by the copyright owner
   or by an individual or Legal Entity authorized to submit on behalf of
   the copyright owner. For the purposes of this definition, "submitted"
   means any form of electronic, verbal, or written communication sent
   to the Licensor or its representatives, including but not limited to
   communication on electronic mailing lists, source code control systems,
   and issue tracking systems that are managed by, or on behalf of, the
   Licensor for the purpose of discussing and improving the Work, but
   excluding communication that is conspicuously marked or otherwise
   designated in writing by the copyright owner as "Not a Contribution."

   "Contributor" shall mean Licensor and any individual or Legal Entity
   on behalf of whom a Contribution has been received by Licensor and
   subsequently incorporated within the Work.

2. Grant of Copyright License. Subject to the terms and conditions of
   this License, each Contributor hereby grants to You a perpetual,
   worldwide, non-exclusive, no-charge, royalty-free, irrevocable
   copyright license to reproduce, prepare Derivative Works of,
   publicly display, publicly perform, sublicense, and distribute the
   Work and such Derivative Works in Source or Object form.

3. Grant of Patent License. Subject to the terms and conditions of
   this License, each Contributor hereby grants to You a perpetual,
   worldwide, non-exclusive, no-charge, royalty-free, irrevocable
   (except as stated in this section) patent license to make, have made,
   use, offer to sell, sell, import, and otherwise transfer the Work,
   where such license applies only to those patent claims licensable
   by such Contributor that are necessarily infringed by their
   Contribution(s) alone or by combination of their Contribution(s)
   with the Work to which such Contribution(s) was submitted. If You
   institute patent litigation against any entity (including a
   cross-claim or counterclaim in a lawsuit) alleging that the Work
   or a Contribution incorporated within the Work constitutes direct
   or contributory patent infringement, then any patent licenses
   granted to You under this License for that Work shall terminate
   as of the date such litigation is filed.

4. Redistribution. You may reproduce and distribute copies of the
   Work or Derivative Works thereof in any medium, with or without
   modifications, and in Source or Object form, provided that You
   meet the following conditions:

   (a) You must give any other recipients of the Work or
       Derivative Works a copy of this License; and

   (b) You must cause any modified files to carry prominent notices
       stating that You changed the files; and

   (c) You must retain, in the Source form of any Derivative Works
       that You distribute, all copyright, patent, trademark, and
       attribution notices from the Source form of the Work,
       excluding those notices that do not pertain to any part of
       the Derivative Works; and

   (d) If the Work includes a "NOTICE" text file as part of its
       distribution, then any Derivative Works that You distribute must
       include a readable copy of the attribution notices contained
       within such NOTICE file, excluding those notices that do not
       pertain to any part of the Derivative Works, in at least one
       of the following places: within a NOTICE text file distributed
       as part of the Derivative Works; within the Source form or
       documentation, if provided along with the Derivative Works; or,
       within a display generated by the Derivative Works, if and
       wherever such third-party notices normally appear. The contents
       of the NOTICE file are for informational purposes only and
       do not modify the License. You may add Your own attribution
       notices within Derivative Works that You distribute, alongside
       or as an addendum to the NOTICE text from the Work, provided
       that such additional attribution notices cannot be construed
       as modifying the License.

   You may add Your own copyright statement to Your modifications and
   may provide additional or different license terms and conditions
   for use, reproduction, or distribution of Your modifications, or
   for any such Derivative Works as a whole, provided Your use,
   reproduction, and distribution of the Work otherwise complies with
   the conditions stated in this License.

5. Submission of Contributions. Unless You explicitly state otherwise,
   any Contribution intentionally submitted for inclusion in the Work
   by You to the Licensor shall be under the terms and conditions of
   this License, without any additional terms or conditions.
   Notwithstanding the above, nothing herein shall supersede or modify
   the terms of any separate license agreement you may have executed
   with Licensor regarding such Contributions.

6. Trademarks. This License does not grant permission to use the trade
   names, trademarks, service marks, or product names of the Licensor,
   except as required for reasonable and customary use in describing the
   origin of the Work and reproducing the content of the NOTICE file.

7. Disclaimer of Warranty. Unless required by applicable law or
   agreed to in writing, Licensor provides the Work (and each
   Contributor provides its Contributions) on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
   implied, including, without limitation, any warranties or conditions
   of TITLE, NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A
   PARTICULAR PURPOSE. You are solely responsible for determining the
   appropriateness of using or redistributing the Work and assume any
   risks associated with Your exercise of permissions under this License.

8. Limitation of Liability. In no event and under no legal theory,
   whether in tort (including negligence), contract, or otherwise,
   unless required by applicable law (such as deliberate and grossly
   negligent acts) or agreed to in writing, shall any Contributor be
   liable to You for damages, including any direct, indirect, special,
   incidental, or consequential damages of any character arising as a
   result of this License or out of the use or inability to use the
   Work (including but not limited to damages for loss of goodwill,
   work stoppage, computer failure or malfunction, or any and all
   other commercial damages or losses), even if such Contributor
   has been advised of the possibility of such damages.

9. Accepting Warranty or Additional Liability. While redistributing
   the Work or Derivative Works thereof, You may choose to offer,
   and charge a fee for, acceptance of support, warranty, indemnity,
   or other liability obligations and/or rights consistent with this
   License. However, in accepting such obligations, You may act only
   on Your own behalf and on Your sole responsibility, not on behalf
   of any other Contributor, and only if You agree to indemnify,
   defend, and hold each Contributor harmless for any liability
   incurred by, or claims asserted against, such Contributor by reason
   of your accepting any such warranty or additional liability.

END OF TERMS AND CONDITIONS
```

---

### LLVM Exceptions to the Apache 2.0 License

```
As an exception, if, as a result of your compiling your source code, portions
of this Software are embedded into an Object form of such source code, you
may redistribute such embedded portions in such Object form without complying
with the conditions of Sections 4(a), 4(b) and 4(d) of the License.

In addition, if you combine or link compiled forms of this Software with
software that is licensed under the GPLv2 ("Combined Software") and if a
court of competent jurisdiction determines that the patent provision (Section
3), the defense provision (Section 8) or any other provision of the License
conflicts with the conditions of the GPLv2, you may retroactively and
prospectively choose to deem Section 3 or Section 8 of the License to be
waived with respect to such Combined Software.
```
