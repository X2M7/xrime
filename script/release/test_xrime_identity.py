#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later

"""Guard against publishing xrime through the historical Trime channel."""

import unittest

import publish


class XrimePublicationGuardTest(unittest.TestCase):
    def test_legacy_publisher_rejects_before_using_inputs(self):
        with self.assertRaisesRegex(RuntimeError, "publisher is disabled"):
            publish.publish(None, None, None)


if __name__ == "__main__":
    unittest.main()
