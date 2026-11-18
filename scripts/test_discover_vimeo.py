import unittest
from discover_vimeo import enrich_duplicates

class DiscoveryTests(unittest.TestCase):
    def test_only_ambiguous_service_durations_are_looked_up(self):
        entries = [{'id':'1','title':'Livestream 08-09-26','duration':3377},
                   {'id':'2','title':' LIVEstream   08-09-26 '},
                   {'id':'3','title':'Livestream 08-16-26'},
                   {'id':'4','title':'Message'}, {'id':'5','title':'Message'}]
        calls=[]
        def lookup(i): calls.append(i); return 1287
        result=enrich_duplicates(entries,lookup)
        self.assertEqual(calls,['2'])
        self.assertEqual(result[0]['duration'],3377)
        self.assertEqual(result[1]['duration'],1287)
    def test_lookup_failure_preserves_both_candidates(self):
        entries=[{'id':'1','title':'Livestream'},{'id':'2','title':'Livestream'}]
        def fail(i): raise RuntimeError('offline')
        self.assertEqual(enrich_duplicates(entries,fail),entries)

if __name__=='__main__': unittest.main()
