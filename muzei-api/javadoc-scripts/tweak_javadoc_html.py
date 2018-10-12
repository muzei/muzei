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

from bs4 import BeautifulSoup


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
        toroot = ('..' + os.sep) * (subpath.count(os.sep))

      html = process(toroot, html)
      if f.endswith('package-summary.html'):
        html = process_package_summary(toroot, html)

      fp = open(os.path.join(path, f), 'w')
      fp.write(html)
      fp.close()


def process(toroot, html):
  soup = BeautifulSoup(html, 'html.parser')
  try:
    subTitle = soup.find(class_='header').find(class_='subTitle')
    link = soup.new_tag('a', href='package-summary.html')
    link.string = subTitle.encode_contents(formatter='html')
    backIcon = soup.new_tag('i', **{'class':'material-icons'})
    backIcon.string = 'arrow_back'
    link.insert(0, backIcon)
    subTitle.clear()
    subTitle.append(link)
  except:
    pass

  prettyprints = soup.find_all('pre', class_='prettyprint')
  for p in prettyprints:
    p.string = re.sub(r'\s+$', '', p.string, re.M | re.S | re.I)
  soup.head.append(soup.new_tag('link', rel='stylesheet', href='http://fonts.googleapis.com/css?family=Roboto:400,700,300|Roboto+Mono'))
  soup.head.append(soup.new_tag('link', rel='stylesheet', href='https://fonts.googleapis.com/icon?family=Material+Icons'))
  soup.head.append(soup.new_tag('link', rel='stylesheet', href=toroot + 'resources/prettify.css'))
  soup.head.append(soup.new_tag('link', rel='stylesheet', href=toroot + 'resources/javadoc_stylesheet.css'))
  soup.head.append(soup.new_tag('script', src=toroot + 'resources/prettify.js'))
  if soup.body:
    script = soup.new_tag('script')
    script.string = 'prettyPrint();'
    soup.body.append(script)
  return soup.encode_contents(formatter='html')


def process_package_summary(toroot, html):
  return html


if __name__ == '__main__':
  main()
