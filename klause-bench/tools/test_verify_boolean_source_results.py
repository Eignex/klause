import copy
from pathlib import Path
import unittest
from unittest import mock

import verify_boolean_source_results as proof_check


class BooleanSourceCheckTest(unittest.TestCase):
    case = dict(index=0, status='DONE', problem={'format': 'DIMACS'},
                record=dict(feasible=True, proven=False, finalWitness='v -1 2',
                            sourceHashes={'model': 'source'}))

    def test_multiline_cnf_and_satlib_terminator_preserve_source_clauses(self):
        case = copy.deepcopy(self.case)
        case['record']['proven'] = True
        with mock.patch.object(proof_check, 'source', return_value='p cnf 2 2\n1\n2 0\n-1 0\n%\n0\n'):
            result = proof_check.check(case, Path('.'))
        self.assertEqual(result['proof'], 'source satisfaction')

    def test_weighted_source_objective_matches_both_witness_formats(self):
        for source, witness in [('p wcnf 2 2 10\n10 1 2 0\n3 1 0\n', 'v -1 2'),
                                ('h 1 2 0\n3 1 0\n', 'v 01')]:
            with self.subTest(source=source):
                case = copy.deepcopy(self.case)
                case['problem']['format'] = 'WCNF'
                case['record'].update(finalWitness=witness, exactObjective='3')
                with mock.patch.object(proof_check, 'source', return_value=source):
                    result = proof_check.check(case, Path('.'))
                self.assertEqual(result['objective'], 3)
                self.assertEqual(result['witness'], 'source feasible')

    def test_numeric_objective_disagreement_is_checked_per_record(self):
        first = copy.deepcopy(self.case)
        first['problem']['format'] = 'WCNF'
        first['record'].update(finalWitness='v -1 2', exactObjective=None, objective=3)
        second = copy.deepcopy(first)
        second['record']['objective'] = 2
        with mock.patch.object(proof_check, 'source', return_value='h 1 2 0\n3 1 0\n'):
            result = proof_check.check_cases([first, second], Path('.'))
        self.assertEqual(result[0]['check']['objective'], 3)
        self.assertIn('error', result[1])

    def test_duplicate_missing_and_out_of_range_coordinates_are_rejected(self):
        for witness in ['v 1 1', 'v 1', 'v 1 3']:
            with self.subTest(witness=witness):
                case = copy.deepcopy(self.case)
                case['record']['finalWitness'] = witness
                with mock.patch.object(proof_check, 'source', return_value='p cnf 2 1\n1 2 0\n'):
                    result = proof_check.check_cases([case], Path('.'))
                self.assertIn('error', result[0])

    def test_feasible_boolean_source_cannot_certify_refutation(self):
        case = copy.deepcopy(self.case)
        case['record'].update(feasible=False, proven=True, finalWitness=None)
        with mock.patch.object(proof_check, 'source', return_value='p cnf 2 1\n1 2 0\n'):
            result = proof_check.check_cases([case], Path('.'))
        self.assertIn('source counterexample', result[0]['error'])

    def test_suboptimal_opb_witness_cannot_certify_optimum(self):
        case = copy.deepcopy(self.case)
        case['problem']['format'] = 'OPB'
        case['record'].update(proven=True, finalWitness='v -x1 x2', exactObjective='2')
        source = '* #variable= 2 #constraint= 1\nmin: +1 x1 +2 x2;\n+1 ~x1 +1 x2 >= 1;\n'
        with mock.patch.object(proof_check, 'source', return_value=source):
            result = proof_check.check_cases([case], Path('.'))
        self.assertIn('source counterexample', result[0]['error'])

    def test_satisfaction_sources_cannot_validate_a_claimed_objective(self):
        for format_name, source, witness in [('DIMACS', 'p cnf 2 1\n1 2 0\n', 'v -1 2'),
                                              ('OPB', '* #variable= 2 #constraint= 1\n+1 x1 +1 x2 >= 1;\n', 'v -x1 x2')]:
            with self.subTest(format=format_name):
                case = copy.deepcopy(self.case)
                case['problem']['format'] = format_name
                case['record'].update(finalWitness=witness, exactObjective='1')
                with mock.patch.object(proof_check, 'source', return_value=source):
                    result = proof_check.check_cases([case], Path('.'))
                self.assertIn('source has no objective', result[0]['error'])

    def test_opb_satisfaction_needs_no_optimization_claim(self):
        case = copy.deepcopy(self.case)
        case['problem']['format'] = 'OPB'
        case['record'].update(finalWitness='v -x1 x2', proven=True)
        source = '* #variable= 2 #constraint= 1\n+1 x1 +1 x2 >= 1;\n'
        with mock.patch.object(proof_check, 'source', return_value=source):
            result = proof_check.check(case, Path('.'))
        self.assertEqual(result['proof'], 'source satisfaction')

    def test_nonlinear_opb_source_is_declined(self):
        case = copy.deepcopy(self.case)
        case['problem']['format'] = 'OPB'
        case['record'].update(finalWitness='v -x1 x2', exactObjective='2')
        source = '* #variable= 2 #constraint= 1\nmin: +2 x2;\n+1 x1 x2 >= 1;\n'
        with mock.patch.object(proof_check, 'source', return_value=source):
            result = proof_check.check_cases([case], Path('.'))
        self.assertIn('error', result[0])


if __name__ == '__main__':
    unittest.main()
