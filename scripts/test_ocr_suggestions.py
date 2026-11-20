import unittest
from ocr_suggestions import propose_details, apply_suggestions, service_date_from_title

class SuggestionTests(unittest.TestCase):
    def test_service_date_from_download_title(self):
        for title, expected in [('Livestream 07-05-26', '2026-07-05'), ('LIVEstream10-25-2026', '2026-10-25'),
                                ('East White Oak Bible Church Live Stream 07-28-24', '2024-07-28'),
                                ('06-23-24', '2024-06-23'),
                                ('Livestream 9/13/26 Picnic', '2026-09-13'), ('Livestream 02-29-24', '2024-02-29')]:
            self.assertEqual(expected, service_date_from_title(title))
        for title in ['Message 07-05-26', 'Livestream 02-30-26', 'Livestream 13-05-26', 'Livestream', 'Livestream 7-5-20260']:
            self.assertEqual('', service_date_from_title(title))
        draft = {'title': 'Livestream 07-05-26', 'date': '2026-07-06'}
        self.assertEqual('2026-07-06', apply_suggestions(draft, [])['date'])
        self.assertEqual('2026-07-05', apply_suggestions(dict(draft, date=''), [])['date'])

    def test_explicit_song_credits_are_proposed_with_source_and_no_guessed_start(self):
        result = propose_details([{'index': 10, 'ocr': 'Be Thou My Vision\n"Be Thou My Vision" by Eleanor Hull and Mary Byrne. Public Domain. CCLI License #123'}])
        song = result['songs'][0]
        self.assertEqual('Be Thou My Vision', song['title'])
        self.assertEqual('Eleanor Hull and Mary Byrne', song['author'])
        self.assertEqual(10, song['evidence-index'])
        self.assertEqual('0:30', song['slide'])
        self.assertEqual('', song['start'])

    def test_no_by_credit_stops_author_before_copyright_year_and_publisher(self):
        text = 'Love The Lord\n"Love The Lord" Lincoln Brewster 2005 Integrity’s Praise! Music CCLI License #562389'
        song = propose_details([{'index': 501, 'ocr': text}])['songs'][0]
        self.assertEqual('Love The Lord', song['title'])
        self.assertEqual('Lincoln Brewster', song['author'])
        self.assertEqual('25:03', song['slide'])
        self.assertEqual(501, song['evidence-index'])
        self.assertEqual('', song['start'])

    def test_no_by_credit_accepts_curly_quotes_and_wrapped_authors(self):
        text = 'A Song\n“A Song” Writer One;\nWriter Two © 2010 Publisher CCLI #1'
        song = propose_details([{'index': 10, 'ocr': text}])['songs'][0]
        self.assertEqual('Writer One; Writer Two', song['author'])

    def test_no_by_credit_does_not_guess_author_from_publisher_or_lyrics(self):
        for text in ['A Song\n"A Song" © 2005 Publisher CCLI #1',
                     'A Song\n"A Song" 2005 Publisher CCLI #1',
                     'A Song\n"A Song" Writer CCLI #1',
                     'Newsletter\n"A Song" Writer 2005 CCLI #1']:
            with self.subTest(text=text):
                self.assertEqual([], propose_details([{'index': 10, 'ocr': text}])['songs'])

    def test_ccli_license_with_repeated_title_is_retained_for_review(self):
        text = ('Christ Our Glory\n"Christ Our Glory" Sovereign Grace Sovereign Grace Praise; '
                'Sovereign Grace Worship CCLI License #562389')
        row = {'index': 457, 'ocr': text}
        song, = propose_details([row])['songs']
        self.assertEqual(('Christ Our Glory', '', '22:51', 457),
                         (song['title'], song['author'], song['slide'], song['evidence-index']))
        for unrelated in [text.splitlines()[1], text.replace('CCLI License #562389', 'CCLI'),
                          text.replace('Christ Our Glory\n', 'Welcome\n')]:
            self.assertEqual([], propose_details([dict(row, ocr=unrelated)])['songs'])
        explicit = {'index': 458, 'ocr': 'Christ Our Glory\n"Christ Our Glory" by Example Writer. CCLI License #562389'}
        credited, = propose_details([row, explicit])['songs']
        self.assertEqual('Example Writer', credited['author'])
        self.assertEqual([457], credited['evidence-alternates'])

    def test_wrapped_mixed_credit_retains_performance_for_author_review(self):
        text = ("Where Can I Go That My Maker Won't Be\n"
                '"Where Can I Go That My Maker Won\'t Be"\n'
                "Jason Houtsma; Natalie Leonhardt; Zach Bolen Black MTN Music; "
                "Half/Alive; Integrity's Praise! Music; Running Club Anthems CCLI License\n#562389")
        song, = propose_details([{'index': 326, 'ocr': text}])['songs']
        self.assertEqual("Where Can I Go That My Maker Won't Be", song['title'])
        self.assertEqual('', song['author'])
        self.assertEqual(('16:18', 326), (song['slide'], song['evidence-index']))
        self.assertIn('Author credit needs manual review', song['suggestion-notes'])
        for invalid in [text.replace('CCLI License\n#562389', 'CCLI'),
                        text.replace("Where Can I Go That My Maker Won't Be\n", 'Welcome\n', 1),
                        text.replace('Jason Houtsma;', '"Unrelated quote" Jason Houtsma;')]:
            self.assertEqual([], propose_details([{'index': 326, 'ocr': invalid}])['songs'])

    def test_personal_names_before_license_without_by_or_copyright(self):
        for credit in ['Albert L. Peace; George Matheson', 'Albert L. Peace;\nGeorge Matheson',
                       'George Matheson', 'Anne-Marie O’Neill']:
            text = f'O Love That Will Not Let Me Go\n“O Love That Will Not Let Me Go” {credit} CCLI License #562389'
            song, = propose_details([{'index': 558, 'ocr': text}])['songs']
            self.assertEqual(' '.join(credit.split()), song['author'])
            self.assertEqual(('27:54', 558, ''), (song['slide'], song['evidence-index'], song['start']))

    def test_license_credit_rejects_ambiguous_attribution(self):
        for credit in ['Sovereign Grace Praise; Sovereign Grace Worship', 'Example Music',
                       'All Rights Reserved', '2005 Publisher', 'Writer', 'CCLI',
                       'come all you weary', 'JOHN SMITH', 'John Smith;']:
            text = f'A Song\n"A Song" {credit} CCLI License #123'
            song, = propose_details([{'index': 10, 'ocr': text}])['songs']
            self.assertEqual('', song['author'], credit)
        for text in ['Welcome\n"A Song" George Matheson CCLI License #123',
                     '"A Song" George Matheson CCLI License #123',
                     'A Song\n"A Song" George Matheson CCLI #123']:
            self.assertEqual([], propose_details([{'index': 10, 'ocr': text}])['songs'])

    def test_ocr_variations_of_nearby_credits_do_not_create_duplicate_performances(self):
        result = propose_details([
            {'index': 10, 'ocr': '"Great Is Thy Faithfulness" by Thomas O. Chisholm. Public Domain. CCLI #1'},
            {'index': 12, 'ocr': '"Creat Is Thy Faithfulness" by Thomas Chisholm. Public Domain. CCLI #1'}])
        self.assertEqual(1, len(result['songs']))
        self.assertEqual([12], result['songs'][0]['evidence-alternates'])

    def test_public_domain_without_author_is_retained_for_review(self):
        text = ('How Firm A Foundation\ntRnAFoandıti\nHow Firm A Foundation\n'
                '"How Firm A Foundation" Public Domain. CCLI License #562389')
        songs = propose_details([{'index': 211, 'ocr': text}, {'index': 212, 'ocr': text}])['songs']
        self.assertEqual(1, len(songs))
        song = songs[0]
        self.assertEqual('How Firm A Foundation', song['title'])
        self.assertEqual('', song['author'])
        self.assertEqual('10:33', song['slide'])
        self.assertEqual(211, song['evidence-index'])
        self.assertIn('No author', song['suggestion-notes'])
        self.assertEqual([212], song['evidence-alternates'])
        for text in ['Newsletter\n"An article" Public Domain. CCLI #1',
                     'How firm a foundation, you saints of the Lord',
                     'A Song\nPublic Domain. CCLI #1']:
            with self.subTest(text=text):
                self.assertEqual([], propose_details([{'index': 211, 'ocr': text}])['songs'])

    def test_uncredited_heading_followed_by_lyrics_uses_optional_start_fallback(self):
        rows = [{'index': 597, 'ocr': 'Christ Our Wisdom\nChrist Our Wisdom'},
                {'index': 598, 'ocr': 'Christ our wisdom, we are humbled\nWhen You hide Your ways from us;\nYou have purposes unnumbered,\nEach one good and glorious.'}]
        song = propose_details(rows)['songs'][0]
        self.assertEqual('Christ Our Wisdom', song['title'])
        self.assertEqual('', song['author'])
        self.assertEqual('', song['slide'])
        self.assertEqual('29:51', song['start'])
        self.assertEqual(597, song['evidence-index'])
        self.assertIn('not a verified musical start', song['suggestion-notes'])
        self.assertEqual([], propose_details(rows[:1])['songs'])
        self.assertEqual([], propose_details([rows[0], dict(rows[1], index=610)])['songs'])
        self.assertEqual([], propose_details([rows[0], dict(rows[1], ocr='An unrelated announcement\nwith three lines\nand plenty of words to pass the text length check')])['songs'])
        self.assertEqual([], propose_details([dict(rows[0], ocr='Welcome Everyone\nWelcome Everyone'), rows[1]])['songs'])

    def test_sermon_screen_is_a_flagged_proposal_with_original_slide_time(self):
        result = propose_details([{'index': 897, 'ocr': 'From\nJERUSALEM\nROME\n"How to Accept the Miracle" Acts 11:1-18'}])
        self.assertEqual('How to Accept the Miracle', result['sermon']['sermon-title'])
        self.assertEqual('Acts 11:1-18', result['sermon']['sermon-scripture'])
        self.assertEqual('44:51', result['sermon']['sermon-time'])
        self.assertEqual(897, result['sermon']['sermon-evidence-index'])
        self.assertEqual('From JERUSALEM ROME', result['sermon']['sermon-series'])
        self.assertIn('Verify', result['sermon']['sermon-suggestion-notes'])

    def test_lyrics_newsletter_and_faces_do_not_supply_unfounded_fields(self):
        records = [{'index': 5, 'ocr': 'NEWSLETTER\nby Pastor Someone\n"A short article" Acts 13:3'},
                   {'index': 7, 'ocr': 'Great is thy faithfulness, Lord unto me'}]
        result = apply_suggestions({'preacher': '', 'leader': '', 'songs': []}, records)
        self.assertEqual([], result['songs'])
        self.assertEqual('', result['preacher'])
        self.assertEqual('', result['leader'])
        self.assertNotIn('sermon-title', result)

if __name__ == '__main__': unittest.main()
