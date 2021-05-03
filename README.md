# Lumberjack
Exploring automated BPE-like approaches to reduce the size of parse-trees

## The idea behind Lumberjack

In ML4SE we often deal with large vocabularies of code tokens (10,000+ or even 100,000+). Meanwhile, the vocabulary of node types in ASTs or parse-trees is way smaller: 100-200 for most programming languages. It leads to two consequences:
- Embeddings of node types (which we use in many ML models) are not that meaningful
- Trees are quite large (since simple operations like assignment to a variable require a whole subtree of 5-6 nodes) and models struggle to learn from them

It brings us the following idea: what if we can automatically detect frequently occurring code constructs in the ASTs and compress them into new nodes? In NLP people do similar things with BPE-like approaches: they start from a vocabulary with individual characters and iteratively merge them into larger words.

## What Lumberjack can do

Currently it is a prototype of a tool that can:
- Parse datasets in Java and Python
- Compress the most frequent edge K times to get smaller trees + larger node type vocabularies
- Handle up to millions of files
