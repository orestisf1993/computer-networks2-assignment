#!/usr/bin/env python
import json
import re
import sys

try:
    import urlparse
except ImportError:
    import urllib.parse as urlparse

import requests
from bs4 import BeautifulSoup

ENCODING = 'ISO-8859-7'
NAME_ENCODED = 'Ορέστης'.encode(ENCODING)
LAST_NAME_ENCODED = 'Φλώρος-Μαλιβίτσης'.encode(ENCODING)
AEM = '7796'
SUBMIT_DATA = {'fi': NAME_ENCODED, 'fa': LAST_NAME_ENCODED, 'am': AEM, 'x': 1}
BASE_URL = r'http://ithaki.eng.auth.gr/netlab/'
LOGIN_URL = r'http://ithaki.eng.auth.gr/netlab/vlabStart.php'
TEXT_FOR_CODES_LINK = r'Δικτυακός προγραμματισμός : Java network socket programming (8ο εξάμηνο)'
CODES_URL_FORMAT = (
    r'http://ithaki.eng.auth.gr/netlab/vlabProject.php?'
    r'session={session}&x=2&fi={name}&fa={last_name}&am=7796'
)


def remove_extra_whitespace(text):
    return " ".join(text.split()).strip()


def get_session_id(html):
    pattern = r'session=(\d{1,})'
    return re.search(pattern, html).groups()[0]


def main():
    with requests.Session() as session:
        login_response = session.post(LOGIN_URL, data=SUBMIT_DATA)
        login_response.encoding = ENCODING

        session_id = get_session_id(login_response.text)
        codes_url = CODES_URL_FORMAT.format(
            session=session_id,
            name=urlparse.quote(NAME_ENCODED),
            last_name=urlparse.quote(LAST_NAME_ENCODED)
        )
        codes_response = session.get(codes_url)
        codes_response.encoding = ENCODING
        codes_soup = BeautifulSoup(codes_response.text, "html.parser")
        codes_text = remove_extra_whitespace(codes_soup.text)

        values = [remove_extra_whitespace(code).split()[0] for code in codes_text.split(':')[2:8]]
        keys = [
            'clientPublicAddress',
            'clientListeningPort',
            'serverListeningPort',
            'echoRequestCode',
            'imageRequestCode',
            'soundRequestCode'
        ]
        dict_to_export = {key: int(value) if value.isdecimal() else value for key, value in zip(keys, values)}
        with open('codes.json', 'w') as results_file:
            json.dump(dict_to_export, results_file)

    return 0


if __name__ == '__main__':
    sys.exit(main())
