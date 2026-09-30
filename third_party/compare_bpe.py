import json
import re
import sys
from pathlib import Path

sys.path.insert(0, 'third_party/indictrans2-python-test')
from tokenizers import Tokenizer

p = Path('app/src/main/assets/models/indictrans2/tokenizer_src.json')
j = json.loads(p.read_text(encoding='utf-8'))
vocab = j['model']['vocab']
ranks = {tuple(pair): i for i, pair in enumerate(j['model']['merges'])}
reference = Tokenizer.from_file(str(p))

def mine(text, target='hin_Deva'):
    ids = [vocab['eng_Latn'], vocab[target]]
    for word in text.split():
        pieces = ['▁', *word]
        while len(pieces) > 1:
            choices = [(ranks.get((pieces[i], pieces[i+1]), 999999), i) for i in range(len(pieces)-1)]
            rank, index = min(choices)
            if rank == 999999:
                break
            pieces[index:index+2] = [pieces[index] + pieces[index+1]]
        ids.extend(vocab.get(piece, 3) if vocab.get(piece, 3) < 32322 else 3 for piece in pieces)
    return ids + [2]

for text in [
    'On 30 Sep, audio evidence was captured and sealed.',
    'A scream was detected with 80 percent confidence.',
    'At 10:30, the noise was recorded.',
]:
    actual = mine(text)
    expected = [i if i < 32322 else 3 for i in reference.encode('eng_Latn hin_Deva ' + text).ids]
    print(text)
    print('expected', expected)
    print('actual  ', actual)
    print('match   ', expected == actual)
