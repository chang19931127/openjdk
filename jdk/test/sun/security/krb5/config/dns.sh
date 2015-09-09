#
# Copyright (c) 2012, 2013, Oracle and/or its affiliates. All rights reserved.
# DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
#
# This code is free software; you can redistribute it and/or modify it
# under the terms of the GNU General Public License version 2 only, as
# published by the Free Software Foundation.
#
# This code is distributed in the hope that it will be useful, but WITHOUT
# ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
# FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
# version 2 for more details (a copy is included in the LICENSE file that
# accompanied this code).
#
# You should have received a copy of the GNU General Public License version
# 2 along with this work; if not, write to the Free Software Foundation,
# Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
#
# Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
# or visit www.oracle.com if you need additional information or have any
# questions.
#

# @test
# @bug 8002344
# @summary Krb5LoginModule config class does not return proper KDC list from DNS
#

if [ "${TESTJAVA}" = "" ] ; then
  JAVAC_CMD=`which javac`
  TESTJAVA=`dirname $JAVAC_CMD`/..
  COMPILEJAVA="${TESTJAVA}"
fi

if [ "${TESTSRC}" = "" ] ; then
   TESTSRC="."
fi

mkdir -p modules/java.naming

$COMPILEJAVA/bin/javac ${TESTJAVACOPTS} ${TESTTOOLVMOPTS} -d . \
        -XaddExports:java.security.jgss/sun.security.krb5=ALL-UNNAMED \
        ${TESTSRC}/DNS.java || exit 1

$COMPILEJAVA/bin/javac ${TESTJAVACOPTS} ${TESTTOOLVMOPTS} -d modules/java.naming \
        ${TESTSRC}/NamingManager.java || exit 2

$TESTJAVA/bin/java ${TESTVMOPTS} \
        -XaddExports:java.security.jgss/sun.security.krb5=ALL-UNNAMED \
        -Xoverride:modules DNS
