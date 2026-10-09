# Enigma flattened witness fixture

`enigma_248_add_or_multiply.fzn` is the original model compiled with MiniZinc 2.9.7 and Klause's globals library at `58ae735e7b527a9d5e1050a0111b78f38b8590f0`. Generated comments containing local paths were removed. All 129 constraints and 90 scalar range bounds are retained; the test adds integer pins without recompiling or simplifying them.

Source: [Hakan Kjellerstrand's model](https://github.com/rasros/hakank/blob/cfdfb67f9a22836ab6b9de0ee3940c6947742bec/minizinc/enigma_248_add_or_multiply/enigma_248_add_or_multiply.mzn).

Source SHA-256: `30663fd9b31d5247bee948ab34ded177684300c9b993dcf0c3120fa11b766484`.
Fixture SHA-256: `fad0d2404c876709934af756c4b83edc1d0f5ea97ce5d9b5f4f743fa4c30f27f`.

The witness in half-pennies is `(2,4,6)`, `(2,3,10)`, `(1,8,9)`, `(1,6,14)`, `(1,5,24)`. The source test checks the solver's returned assignment against every original predicate and bound using direct arithmetic. All witness arithmetic is dyadic and exactly representable in binary64.

MIT License

Copyright (c) 2019 Hakan Kjellerstrand

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
