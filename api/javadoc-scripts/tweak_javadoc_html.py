#!/usr/bin/env python
# Copyright 2014 Google Inc.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import re
import os
import sys
import shutil


PACKAGE_NAME = 'com.google.android.apps.muzei.api'


def main():
  root = sys.argv[1]
  for path, _, files in os.walk(root):
    for f in [f for f in files if f.endswith('.html')]:
      fp = open(os.path.join(path, f), 'r')
      html = fp.read()
      fp.close()

      toroot = '.'
      if path.startswith(root):
        subpath = path[len(root):]
        toroot = '../' * (subpath.count('/') + 1)

      html = process(toroot, html)
      if f.endswith('package-summary.html'):
        html = process_package_summary(toroot, html)

      fp = open(os.path.join(path, f), 'w')
      fp.write(html)
      fp.close()

  shutil.copy('index.html', root)


def process(toroot, html):
  re_flags = re.I | re.M | re.S
  html = re.sub(r'<HR>\s+<HR>', '', html, 0, re_flags)
  html = re.sub(r'windowTitle\(\);', 'windowTitle();prettyPrint();', html, 0, re_flags)
  html = re.sub(r'\s+</PRE>', '</PRE>', html, 0, re_flags)
  html = re.sub(PACKAGE_NAME + '</font>', '<A HREF="package-summary.html" STYLE="border:0">' + PACKAGE_NAME + '</A></FONT>', html, 0, re_flags)
  html = re.sub(r'<HEAD>', '''<HEAD>
<LINK REL="stylesheet" TYPE="text/css" HREF="http://fonts.googleapis.com/css?family=Roboto:400,700,300|Inconsolata">
<LINK REL="stylesheet" TYPE="text/css" HREF="%(root)sresources/prettify.css">
<SCRIPT SRC="%(root)sresources/prettify.js"></SCRIPT>
''' % dict(root=toroot), html, 0, re_flags)
  #html = re.sub(r'<HR>\s+<HR>', '', html, re.I | re.M | re.S)
  return html


def process_package_summary(toroot, html):
  re_flags = re.I | re.M | re.S
  #html = re.sub(r'</H2>\s+.*?\n', '</H2>\n', html, 0, re_flags)
  html = re.sub(r'<B>See:</B>\n<br>', '\n', html, 0, re_flags)
  html = re.sub(r'&nbsp;&nbsp;(&nbsp;)+[^\n]+\n', '\n', html, 0, re_flags)
  html = re.sub(r'\n[^\n]+\s+description\n', '\nDescription\n', html, 0, re_flags)
  return html


if __name__ == '__main__':
  main()
